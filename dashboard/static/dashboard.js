const containersElement = document.querySelector("#containers");
const summaryElement = document.querySelector("#summary");
const updatedElement = document.querySelector("#last-updated");

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  }[character]));
}

function render(data) {
  const containers = data.containers;
  const running = containers.filter((container) => container.status === "running").length;
  const healthy = containers.filter((container) => container.health === "healthy").length;
  summaryElement.innerHTML = `
    <div><strong>${containers.length}</strong><span>containers</span></div>
    <div><strong>${running}</strong><span>running</span></div>
    <div><strong>${healthy}</strong><span>healthy</span></div>
  `;
  containersElement.innerHTML = containers.length ? containers.map((container) => `
    <article class="card">
      <div class="card-header">
        <h2>${escapeHtml(container.service)}</h2>
        <span class="status ${escapeHtml(container.status)}">${escapeHtml(container.status)}</span>
      </div>
      <p class="container-name">${escapeHtml(container.name)}</p>
      <dl>
        <dt>Health</dt><dd class="health ${escapeHtml(container.health)}">${escapeHtml(container.health)}</dd>
        <dt>Image</dt><dd>${escapeHtml(container.image)}</dd>
        <dt>Ports</dt><dd>${container.ports.length ? container.ports.map(escapeHtml).join("<br>") : "—"}</dd>
      </dl>
    </article>
  `).join("") : '<p class="empty">No containers found for this Compose project.</p>';
  updatedElement.textContent = `Updated ${new Date().toLocaleTimeString()}`;
}

async function refresh() {
  try {
    const response = await fetch("/api/containers", { cache: "no-store" });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "Docker is unavailable");
    render(data);
  } catch (error) {
    summaryElement.innerHTML = "";
    containersElement.innerHTML = `<p class="error">${escapeHtml(error.message)}</p>`;
    updatedElement.textContent = "Update failed";
  }
}

document.querySelector("#refresh").addEventListener("click", refresh);
refresh();
setInterval(refresh, 5000);
