# Spectral Camera Site

The blog-style landing page and development journal for [Spectral Camera](https://github.com/renardoberou/spectral-camera), an Android camera for simulated infrared, Aerochrome-style colour, and film rendering.

## Story

The site is built around the project's real origin: a roll of Rollei IR 400 bought in 2009, never developed, and the return of that question through a photograph made in Minas Gerais in 2026.

The page documents:

- the origin story;
- the visible-RGB to synthetic-NIR rendering pipeline;
- the distinction between simulated infrared and real infrared capture;
- field failures and their engineering fixes;
- supplied Minas Gerais before/after results;
- the v2.0 milestone.

## Local preview

From the repository root:

```bash
python3 -m http.server 8765
```

Open <http://127.0.0.1:8765/> in a browser.

Run the dependency-free integrity check:

```bash
python3 scripts/validate-site.py
```

## Structure

- `index.html` — long-form landing page and story
- `styles.css` — responsive editorial design system
- `script.js` — mobile navigation and before/after comparison slider
- `assets/images/` — supplied Minas Gerais field frames
- `assets/brand/` — transparent Spectral Camera mark
- `scripts/validate-site.py` — local-reference and content-integrity check
- `.github/workflows/pages.yml` — validation and GitHub Pages deployment

## Image provenance

The three Minas Gerais photographs were supplied by the project owner for this site:

- `minas-gerais-original-capture.jpg` — original natural-colour source frame
- `minas-gerais-colour-result.jpg` — Spectral Camera colour result
- `minas-gerais-monochrome-ir.jpg` — Spectral Camera monochrome IR result

Exact device, preset, and capture-mode metadata were not supplied, so the page deliberately does not invent those labels.

## Deployment

Pushes to `main` run the validator and deploy the repository root through GitHub Pages. The site uses no build framework or remote runtime dependency.
