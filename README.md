# Java Applet Lab (starter project)

This project is a learning prototype for experimenting with legacy Java Applets from a browser.

## Important limitations

- Java Applets are obsolete and are not supported by modern browsers.
- This project uses a legacy Java 8 environment on the backend. It is not a replacement for the original browser plug-in.
- The backend renders an Applet into a PNG image. Mouse/keyboard events can be sent to the current Applet instance, but interactive behaviour depends on the Applet implementation.
- **Do not expose this prototype publicly as-is.** It compiles and executes user-submitted Java code. Before public deployment, run every submission in a strongly isolated, short-lived sandbox/container with no network, strict CPU/RAM/process/time limits, and a non-privileged user. A normal web-service container alone is not a sufficient security boundary.

## Project structure

- `frontend/` — HTML, CSS, JavaScript interface
- `backend/src/AppletServer.java` — Java 8 HTTP API, compiler, Applet renderer
- `backend/Dockerfile` — legacy Java 8 + virtual display runtime
- `backend/render.yaml` — example Render Blueprint (free availability/limits can change)

## API

- `GET /health`
- `POST /api/run` JSON: `{"code":"..."}`
  - The submitted source must declare `public class AppletProgram extends Applet`.
  - Response: `{"sessionId":"...","image":"data:image/png;base64,...","message":"..."}`
- `POST /api/event` JSON:
  `{"sessionId":"...","type":"mouseClicked","x":40,"y":40}`
  Supported types: `mouseClicked`, `mousePressed`, `mouseReleased`, `mouseMoved`, `mouseDragged`, `keyPressed`, `keyReleased`, `keyTyped`.
- `POST /api/reset` JSON: `{"sessionId":"..."}`

## Run locally (requires Docker)

1. Install Docker.
2. In a terminal, from this folder:
   ```bash
   docker build -t java-applet-lab ./backend
   docker run --rm -p 8080:8080 --memory=512m --cpus=1 --pids-limit=64 java-applet-lab
   ```
3. Open `frontend/index.html` in a browser.
4. In the API URL box, use `http://localhost:8080`.
5. Try the included sample, then press **Run**.

If opening the HTML file directly causes browser restrictions, run a static server from the project folder:
```bash
python -m http.server 5500
```
Then open `http://localhost:5500/frontend/`.

## Deploying

The frontend can be hosted as a static site, e.g. GitHub Pages. The backend needs a Java 8 + virtual-display container and must be hardened before accepting public user code. Free container hosts may sleep, limit resources, or change their terms. Do not assume an always-on free backend.

## Sample code

The editor is prefilled with a simple Applet example. Keep the class name `AppletProgram` because the prototype expects that exact name.
