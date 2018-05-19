Scratch folder for testing out the local checkout of Mill on ad-hoc builds.

To run the current checkout of Mill on the build in the scratch folder, use:

```
mill -i dev.run scratch -w show thingy
```

If you want to avoid having the bootstrap Mill process running while your
locally compiled Mill process is running on the build within `scratch` (e.g. to
simplify debugging) you can use:

```
mill -i dev.launcher && (cd scratch && ../out/dev/launcher/dest/run -w show thingy)
```