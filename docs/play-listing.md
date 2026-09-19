# The Play listing, and every answer the console asks for

The store listing and the App content declarations are kept here so that changing what this project
tells Google is a reviewable diff rather than something somebody typed into a web form once.

**The declarations themselves can only be entered by hand.** Google's publishing API covers the
listing text, the graphics and the release tracks; it does not cover App content, the content-rating
questionnaire or Data safety. Those five sections are a person clicking through the console, which is
why the exact answers are written down below.

## Store listing

**App name** (30 characters maximum)

```
Agterm Remote
```

**Short description** (80 characters maximum)

```
Your agterm terminal sessions on your phone. Pair by scanning one QR code.
```

**Full description** (4000 characters maximum)

```
Agterm Remote puts the terminal sessions running on your own computer onto your phone.

It pairs with a small companion application you install on your Mac, which you can get from the
project's releases page. Pairing is one QR code: the Mac shows it, the phone reads it, and from then
on the two hold each other's keys.

WHAT IT CONNECTS TO

Your computer, and nothing else. There is no cloud service behind this application, no account to
create and no server operated by its author. The phone talks directly to the machine you name, over a
connection both ends authenticate with certificates they exchanged during pairing. How the phone
reaches your computer is up to you - a port forward, a dynamic DNS name, a private network such as
Tailscale or WireGuard, or a reverse proxy you already run.

WHAT IT SHOWS

The sessions and workspaces of agterm, a separate open-source terminal by another author that you
install and run yourself. This application has no terminal of its own and starts no shell: it shows
what agterm is already running. Scrollback, typing, resizing and split panes all work from the phone.

PRIVACY

Nothing is collected. No analytics, no crash reporting, no advertising identifier, no telemetry. The
private key the phone generates during pairing is created inside the device's hardware-backed keystore
and never leaves it. The camera permission is used to read the pairing code and is optional - the code
can be pasted as text instead.

OPEN SOURCE

Apache-2.0, and the whole of it is public: https://github.com/isachivka/agterm-remote
```

**Category:** Tools. **Tags:** terminal, developer tools.

**Privacy policy URL**

```
https://github.com/isachivka/agterm-remote/blob/main/PRIVACY.md
```

## App content — the answers

| Question | Answer |
|---|---|
| Privacy policy | the URL above |
| Ads | **No**, the app contains no ads |
| App access | **All functionality is available without special access.** Nothing is behind a login; pairing needs a computer the reviewer would have to own, which is not a credential this project can supply |
| Content rating | see the questionnaire below |
| Target audience | **18 and over.** Not appealing to children, no child-directed content |
| News app | **No** |
| COVID-19 contact tracing or status | **No** |
| Data safety | see the table below |
| Government apps | **No** |
| Financial features | **None** |
| Health | **No** |

## Content rating questionnaire

Category: **Utility, productivity, communication or other**. Then: violence **no**, sexuality **no**,
language **no**, controlled substances **no**, user-generated content sharing **no**, user
interaction **no**, location sharing **no**, personal information sharing **no**, digital purchases
**no**.

The one question worth pausing on is user interaction: this application connects two devices that
belong to the SAME person. It is not a channel between users, and there is no service through which
strangers could reach one another.

## Data safety

**Does your app collect or share any of the required user data types? No.**

That answer is the whole section, and it is true in the strict sense Google means: data leaves the
device only to the computer the person paired with, which is theirs, and the author operates nothing
that could receive it. Nothing is collected, nothing is shared, and there is no account.

The two follow-up questions Google asks anyway:

| Question | Answer |
|---|---|
| Is all of the user data collected by your app encrypted in transit? | **Yes** - mutually authenticated TLS, and the app refuses any certificate but the one it pinned during pairing |
| Do you provide a way for users to request that their data be deleted? | **Yes** - uninstalling the app destroys everything it holds, including the key, which cannot be recovered afterwards |

## What is NOT in the console yet

Graphics. A 512x512 icon, a 1024x500 feature graphic and at least two phone screenshots are required
before the listing can go public, and none of them are needed for internal testing.
