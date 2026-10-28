const sampleCode = `import java.applet.Applet;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

public class AppletProgram extends Applet
        implements MouseListener, KeyListener {
    private String message = "Hello Java Applet!";
    private int clickCount = 0;

    public void init() {
        setSize(480, 320);
        setBackground(Color.WHITE);
        addMouseListener(this);
        addKeyListener(this);
        setFocusable(true);
    }

    public void paint(Graphics g) {
        g.setColor(new Color(30, 70, 150));
        g.drawString(message, 30, 35);
        g.drawLine(30, 55, 250, 55);
        g.setColor(new Color(35, 160, 110));
        g.drawOval(40, 85, 100, 70);
        g.setColor(new Color(210, 90, 80));
        g.drawRect(180, 85, 110, 70);
        g.setColor(Color.DARK_GRAY);
        g.drawString("Mouse clicks: " + clickCount, 30, 190);
        g.drawString("Click the event buttons below to test events.", 30, 215);
    }

    public void mouseClicked(MouseEvent e) {
        clickCount++;
        message = "Mouse clicked at (" + e.getX() + ", " + e.getY() + ")";
        repaint();
    }
    public void mousePressed(MouseEvent e) {}
    public void mouseReleased(MouseEvent e) {}
    public void mouseEntered(MouseEvent e) {}
    public void mouseExited(MouseEvent e) {}

    public void keyPressed(KeyEvent e) {
        message = "Key pressed: " + KeyEvent.getKeyText(e.getKeyCode());
        repaint();
    }
    public void keyReleased(KeyEvent e) {}
    public void keyTyped(KeyEvent e) {}
}`;
const $ = (id) => document.getElementById(id);
const code = $("code"), consoleOutput = $("consoleOutput"), runBtn = $("runBtn");
let currentSession = null;

code.value = sampleCode;
function updateLineCount() {
  const count = code.value.split("\n").length;
  $("lineCount").textContent = `${count} line${count === 1 ? "" : "s"}`;
}
function log(message) { consoleOutput.textContent = String(message); }
function baseUrl() { return $("apiBase").value.trim().replace(/\/+$/, ""); }
async function postJson(path, data) {
  const response = await fetch(baseUrl() + path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(data)
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
  if (body.error) throw new Error(body.error);
  return body;
}
function showImage(dataUrl) {
  $("outputImage").src = dataUrl;
  $("outputImage").hidden = false;
  $("emptyState").hidden = true;
}
runBtn.addEventListener("click", async () => {
  runBtn.disabled = true;
  $("outputStatus").textContent = "Compiling…";
  log("Sending source to the Java backend…");
  try {
    const result = await postJson("/api/run", {code: code.value});
    currentSession = result.sessionId;
    showImage(result.image);
    $("outputStatus").textContent = "Rendered";
    log(result.message || "Compilation and rendering completed.");
  } catch (err) {
    $("outputStatus").textContent = "Failed";
    log(`Error: ${err.message}\n\nCheck that the backend is running and the API URL is correct.`);
  } finally {
    runBtn.disabled = false;
  }
});
$("resetBtn").addEventListener("click", async () => {
  if (currentSession) {
    try { await postJson("/api/reset", {sessionId: currentSession}); } catch (_) {}
  }
  currentSession = null;
  $("outputImage").hidden = true;
  $("emptyState").hidden = false;
  $("outputStatus").textContent = "Waiting to run";
  log("Output reset. Press Run Applet to compile again.");
});
document.querySelectorAll(".event-btn").forEach((button) => {
  button.addEventListener("click", async () => {
    if (!currentSession) { log("Run an Applet first."); return; }
    const type = button.dataset.event;
    try {
      const result = await postJson("/api/event", {
        sessionId: currentSession, type, x: 90, y: 115, keyCode: 65
      });
      showImage(result.image);
      $("outputStatus").textContent = "Event sent";
      log(result.message || `Sent ${type}.`);
    } catch (err) { log(`Event error: ${err.message}`); }
  });
});
$("clearConsole").addEventListener("click", () => log("Console cleared."));
code.addEventListener("input", updateLineCount);
updateLineCount();
