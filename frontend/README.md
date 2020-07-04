# Amadeus - Frontend

Contains TypeScript/HTML/CSS sources for the frontend; these can be built and served independently of the backend
(though they won't do anything interesting without the backend).

## Build Instructions

The frontend uses `npm` and `rollup`. For initial setup:

```
npm run setup
npm run build
```

and for subsequent builds:

```
npm run build
```

The output files are in `dist/`; just open `dist/index.html`!

## Testing Instructions

We use Jest for testing; tests are located in `test/`; just run

```
npm test
```

to run the test suite using the jest runner.
