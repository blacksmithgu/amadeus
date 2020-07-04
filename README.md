# Amadeus 

A somewhat silly music guessing game; targeted mostly towards video games, but supports generic file uploads as well as
music sourced from Youtube (via youtube-dl).

## Frontend

The frontend is located in the `frontend/` folder, and is written in TypeScript/HTML/CSS; it has it's own build tooling
with `npm` and `rollup`; see the README located there for details.

## Backend

The backend, located in the root of this repository, is written in Kotlin and built with gradle. If you have an IDE
which supports gradle integration (like IntelliJ), simply import this directory as a gradle project and everything will
be taken care of.

If you want to work from the command line, make sure you have a modern JDK installed (at least JDK 8), and then use
this to build:

```bash
./gradlew build
```

And this to test:

```bash
./gradlew test
```

## Current Goals

**Server-side**: 

* [ ] Simple file-upload API for providing music
* [ ] Simple youtube-fetch API for obtaining music from youtube via youtube-dl
    * [ ] Verifier for checking youtube-dl and python3 are both available
    * [ ] Auto-download youtube-dl if not present; can potentially also download standalone python interpreter
* [ ] API for creating a game, joining a game (via URL), and quitting/destroying a game.
* [ ] Music streaming during a game, as well as basic game structure (incl. guessing and game state synchronization).

**Client-side**: 

* [ ] Basic static page for creating a room with a password
* [ ] Web-socket based game page

