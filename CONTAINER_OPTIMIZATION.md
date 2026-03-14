# CI/CD Container Optimization Guide

## Problem

Your current GitHub Actions workflow reinstalls dependencies in each job:
- **Build webapi job**: Node, Java, Clojure, npm packages
- **Build webapp job**: Node, npm packages
- **Validate job**: Node, npm packages, Playwright + Chromium

This happens 3× separately, causing ~5-10 minute overhead on every pipeline run.

## Solution: Container-Based CI

Use a custom Docker image with all tools pre-installed, cached in GitHub Container Registry.

### Benefits

| Aspect | Benefit | Impact |
|--------|---------|--------|
| **Speed** | No tool setup, straight to build | 5-10 min saved per run |
| **Consistency** | Same environment locally & CI | Eliminates "works on my machine" |
| **Maintenance** | Single source of truth (Dockerfile) | Easier to update tool versions |
| **Cost** | GHCR is free for public repos | No extra costs |
| **Flexibility** | Standard Docker format | Works with any CI platform |

### Trade-offs

| Trade-off | Detail | Mitigation |
|-----------|--------|-----------|
| **Initial build** | First image build takes ~5-10 min | Runs once; subsequent runs instant |
| **Maintenance** | Must update Dockerfile for tool version changes | Simple: edit one file |
| **Image size** | ~2.5-3 GB (Node + Java + Clojure + Playwright) | Acceptable for modern CI; stored remotely |
| **Setup complexity** | Need to enable GHCR permissions | Done once in settings |

---

## Implementation Steps

### Step 1: Enable GitHub Container Registry (GHCR)

1. Go to your repository Settings → Packages
2. Ensure "GitHub Container Registry" is enabled
3. Container images will auto-push to `ghcr.io/yourusername/robogene/ci-environment`

### Step 2: Add Dockerfile to repository root

```bash
# File: Dockerfile (already created)
# Contains:
# - Base: Node 22 Bookworm (Debian-based)
# - Java 21 JDK
# - Clojure CLI
# - Azure Functions Core Tools
# - Playwright + Chromium (with all deps)
# - Python 3
```

**Key design decisions:**
- Multi-stage not needed (single build stage is simpler here)
- Installs Playwright with `--with-deps` to avoid missing system libraries
- Cleans npm cache to reduce final image size
- Sets appropriate ENV variables (JAVA_HOME, non-interactive mode)

### Step 3: Update workflow to use container

**Option A: Use the new containerized workflow** (recommended for testing)

```bash
cp .github/workflows/deploy-containerized.yml .github/workflows/deploy.yml.new
# Review the new workflow, then:
mv .github/workflows/deploy.yml.new .github/workflows/deploy.yml
```

**Option B: Gradually migrate** (safer for production)

- Keep `deploy.yml` as-is
- Use `deploy-containerized.yml` on a test branch
- Verify all tests pass
- Merge once confident

### Step 4: Monitor first runs

First pipeline run will:
1. Build the Docker image (~8-10 min)
2. Push to GHCR
3. Run jobs using the image (very fast)

Subsequent runs will:
1. Use cached image from GHCR
2. Skip image build
3. Run all jobs 5-10 min faster

### Step 5: Cache invalidation

The Docker image rebuilds automatically when:
- `Dockerfile` is modified
- Pushed to main branch

To force rebuild: Edit Dockerfile, commit, push.

---

## Architecture Details

### Image Layering

```
Layer 1: Node 22 base image (Bookworm)
Layer 2: System dependencies (curl, java, clojure, build tools)
Layer 3: Set JAVA_HOME, install Clojure CLI
Layer 4: Install Azure Functions Core Tools
Layer 5: Create /workspace, copy package*.json
Layer 6: npm ci (cached, only rebuilds if package files change)
Layer 7: npx playwright install (cached, only rebuilds if playwright version changes)
Layer 8: Cleanup and labels
```

**Cache benefit**: Steps 1-5 cached in registry; npm ci (step 6) only reinstalls if `package.json` changes.

### Workflow Job Changes

**Before** (setup steps per job):
```yaml
jobs:
  build-webapi:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/setup-node@v4      # ← 2-3 min
      - run: npm ci
      - run: npm run build:webapi
```

**After** (no setup needed):
```yaml
jobs:
  build-webapi:
    runs-on: ubuntu-latest
    container:
      image: ghcr.io/yourorg/robogene/ci-environment:latest
    steps:
      - run: npm ci                      # ← instant (cached in image)
      - run: npm run build:webapi
```

---

## Performance Comparison

### Current Pipeline (without container)
```
📊 Timeline for 3 parallel jobs:
├─ build-webapi:      3m setup + 10m build = 13m
├─ build-webapp:      3m setup + 5m build  = 8m
└─ validate:          3m setup + 15m tests = 18m
                                Total: ~18 min
```

### With Container
```
📊 Timeline for 3 parallel jobs:
├─ build-webapi:      0m setup + 10m build = 10m
├─ build-webapp:      0m setup + 5m build  = 5m
└─ validate:          0m setup + 15m tests = 15m
                                Total: ~15 min
                                Saved: ~3 min per run
```

**Note**: First run saves less (image build adds ~8-10 min), but every run after saves ~3-5 min.

---

## Troubleshooting

### Image not found errors

```
Error: Container image not found: ghcr.io/yourorg/robogene/ci-environment:latest
```

**Fix**: Run `build-image` job first, or manually push:
```bash
docker build -t ghcr.io/yourorg/robogene/ci-environment:latest .
docker push ghcr.io/yourorg/robogene/ci-environment:latest
```

### Playwright tests fail in container

Common cause: Missing system dependencies.

**Verify in Dockerfile**:
```dockerfile
RUN npx playwright install --with-deps chromium
```

If still failing, check container logs for library errors.

### Java not found in container

**Verify JAVA_HOME is set**:
```bash
docker run ghcr.io/yourorg/robogene/ci-environment:latest \
  bash -c "echo $JAVA_HOME && java -version"
```

---

## Alternative Approaches (Not Recommended)

### 1. Actions caching (current practice)
- **Pro**: Built into GitHub Actions
- **Con**: Still requires setup steps; cache miss rebuilds everything
- **Verdict**: Slower than containers; good for fallback

### 2. Self-hosted runners
- **Pro**: No startup overhead
- **Con**: Require maintenance, storage, networking
- **Verdict**: Overkill for this project

### 3. Pre-built official images
- **Pro**: Maintained by others
- **Con**: Less control; may need extra setup steps anyway
- **Verdict**: Less optimal than custom image

---

## Next Steps

1. **Review Dockerfile** - Check tool versions match your needs
   - Clojure version: Currently latest from tap (update `linux-install.sh` URL if needed)
   - Node: 22 (matches your current setup)
   - Java: 21 (matches your current setup)
   - Playwright: Latest (auto-installed)

2. **Test on a feature branch**
   - Create `test-container` branch
   - Update `deploy.yml` to use containerized workflow
   - Verify all jobs pass
   - Check wall-clock time savings in Actions tab

3. **Merge to main** once verified

4. **Monitor** first 2-3 runs to ensure consistency

---

## Files

- **Dockerfile** - Custom CI environment image
- **.github/workflows/deploy-containerized.yml** - New containerized workflow
- **.github/workflows/deploy.yml** - Keep as backup during transition

---

## Questions?

Common concerns:
- **"Will it work on Windows/Mac?"** - Containers run Linux; GitHub Actions Ubuntu workers use Linux anyway ✓
- **"What if I need to add a tool?"** - Edit Dockerfile, commit, auto-rebuilds ✓
- **"Can I use this locally?"** - Yes! Run `docker build -t robogene-ci .` and develop in the container
- **"What about security?"** - Container is public (mirrors your public repo anyway); use private registries if needed
