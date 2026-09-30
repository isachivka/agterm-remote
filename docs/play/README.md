# Play graphics

Sources and the exact PNGs uploaded to the Play listing. Rendered with `rsvg-convert`:

```bash
rsvg-convert -w 512  -h 512 docs/play/icon.svg            -o docs/play/icon-512.png
rsvg-convert -w 1024 -h 500 docs/play/feature-graphic.svg -o docs/play/feature-graphic.png
# launcher layers, one per density (mdpi 108 ... xxxhdpi 432)
rsvg-convert -w 432 -h 432 docs/play/launcher-foreground.svg -o app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png
rsvg-convert -w 432 -h 432 docs/play/launcher-monochrome.svg -o app/src/main/res/mipmap-xxxhdpi/ic_launcher_monochrome.png
```

The mark is agterm's grid of sessions with its last cell turned into a phone, on agterm's blue.

## Screenshots

`screenshots/` holds the four phone screenshots on the listing. They were taken on the emulator
against a scratch bridge and a fake agterm serving invented sessions - no real machine's content -
and padded from 1344x2992 to 1496x2992 by repeating the edge columns, because Play refuses a
screenshot whose long side is more than twice its short side.
