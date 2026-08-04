# Releasing

The [plugin hub](https://github.com/runelite/plugin-hub) does not consume tags, releases or
built artifacts. A plugin's entry there is two lines pinning a commit:

```
repository=https://github.com/neldra/kills-to-level.git
commit=<full 40-character sha>
```

The hub clones the repository at that exact commit and builds it itself. Shipping an update means
opening another pull request that changes that one line.

Tagging here is therefore for people, not for the hub — but it is worth doing. `version` in
`runelite-plugin.properties` is optional, and **when it is missing the hub displays the commit
hash as the version**, which is not what you want users to see.

## Before a release

- [ ] `./gradlew build` passes, and CI is green on both JDK 11 and 21
- [ ] The repository is **public** — the hub cannot clone a private one
- [ ] `./gradlew overlayShots` regenerated if the overlay changed, and the README images updated
- [ ] Sanity-checked in a real client (`./gradlew run`); the headless tests cover the logic, but
      only the game confirms it against live event ordering

## Cutting a release

Version numbers live in two files and must agree.

```bash
# 1. bump both, in the same commit
#    runelite-plugin.properties   version=1.1
#    build.gradle                 version = '1.1'
git commit -am "release: 1.1"

# 2. tag and push
git tag -a v1.1 -m "Kills to Level 1.1"
git push origin main --tags

# 3. write the notes
gh release create v1.1 --title "1.1" --notes "..."

# 4. this is the sha the hub needs
git rev-parse v1.1^{commit}   # ^{commit} matters: on an annotated tag, plain rev-parse
                              # returns the tag object, not the commit the hub needs
```

## Submitting to the hub

First time, fork [runelite/plugin-hub](https://github.com/runelite/plugin-hub). After that:

```bash
git remote add upstream https://github.com/runelite/plugin-hub.git   # first time only
git fetch upstream
git checkout -B kills-to-level upstream/master

# create or edit plugins/kills-to-level:
#   repository=https://github.com/neldra/kills-to-level.git
#   commit=<sha from step 4 above>

git add plugins/kills-to-level
git commit -m "update kills-to-level"
git push -f -u origin kills-to-level
gh pr create -w
```

Two checks run on the pull request:

- **build** — the hub builds the plugin. Note it packages `standard` plugins with **its own**
  `build.gradle`, so anything this repository's build file adds is absent there. That is why tests
  needing Mockito live in `src/pluginTest` and not `src/test`; the hub's template declares only
  JUnit, so a Mockito import under `src/test` would fail to compile during packaging.
- **RuneLite Plugin Hub Checks** — only needs attention if it says `Changes are needed.`

Fix problems by pushing more commits to the **same** pull request. The hub maintainers ask for
this explicitly, to avoid a stream of newly opened pull requests.

## Notes

- Adding a dependency that is not already a transitive dependency of `runelite-client` requires a
  maintainer to verify its hash by hand, and the hub warns this significantly slows review. Avoid
  it unless there is no alternative.
- `icon.png` must be no larger than 48x72 pixels and sit at the repository root. Re-derive it from
  `assets/logo-master.png` rather than redrawing it.
