# AlmasFox PC v0.4

Windows 11 helper for Xiaomi Mi A3 (`laurel_sprout`).

## Safe workflow

1. Install/download Google Platform Tools from inside AlmasFox PC.
2. Enable USB debugging and verify the connected device.
3. Select the candidate `boot.img`.
4. AlmasFox copies it to `Download/AlmasFox/AlmasFox-input-boot.img` and opens Magisk.
5. In Magisk, use **Install → Select and Patch a File** once. Magisk does the actual patching.
6. AlmasFox PC finds and pulls `magisk_patched*.img` automatically.
7. The app checks the patched image and reboots to bootloader.
8. If the bootloader is already unlocked, it first runs `fastboot boot` for a temporary test.
9. Permanent `boot_a`/`boot_b` flash is enabled only after Android comes back and `su -c id` returns `uid=0`.

## Data-safety rules

AlmasFox PC never runs bootloader unlock, `fastboot -w`, `erase userdata`, `format userdata`, or automatic vbmeta-disable commands. If the bootloader is locked or the active slot cannot be confirmed, the process stops.

Bootloader unlocking itself can erase user data; AlmasFox PC intentionally does not perform it.
