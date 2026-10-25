# KGV FusionX source patches

One folder per repo in the LineageOS 20 tree, each a `git format-patch`
series on top of the manifest revision (`m/lineage-20.0`). Apply with:

    git -C <repo> am ~/claude/fusionx-port/patches/<repo-with-dashes>/*.patch

The script needs Pillow and numpy (`python3 -m venv branding/.venv && branding/.venv/bin/pip install pillow numpy`).

`device-xiaomi-mido` leaves out the binary boot animation. Rebuild it and
copy it in before building:

    branding/.venv/bin/python branding/make_bootanimation.py \
        branding/src/KGVHub_072012.jpg branding/out
    cp branding/out/bootanimation.zip \
        ../fusionx/device/xiaomi/mido/bootanimation/
