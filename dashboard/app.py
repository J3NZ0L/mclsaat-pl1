"""Read-only dashboard for the containers in the local Docker environment."""

import os
from datetime import datetime, timezone

from flask import Flask, jsonify, render_template


def _container_summary(container):
    attrs = container.attrs
    state = attrs.get("State", {})
    health = state.get("Health", {})
    labels = attrs.get("Config", {}).get("Labels", {}) or {}

    return {
        "id": container.short_id,
        "name": container.name.lstrip("/"),
        "service": labels.get("com.docker.compose.service", container.name.lstrip("/")),
        "image": attrs.get("Config", {}).get("Image", container.image.tags[0] if container.image.tags else ""),
        "status": state.get("Status", container.status),
        "health": health.get("Status", "none"),
        "started_at": _format_timestamp(state.get("StartedAt")),
        "ports": _published_ports(attrs.get("NetworkSettings", {}).get("Ports", {}) or {}),
    }


def _format_timestamp(value):
    if not value or value.startswith("0001-01-01"):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc).isoformat()
    except ValueError:
        return value


def _published_ports(ports):
    published = []
    for container_port, bindings in ports.items():
        for binding in bindings or []:
            host_ip = binding.get("HostIp", "0.0.0.0")
            host_port = binding.get("HostPort")
            if host_port:
                published.append(f"{host_ip}:{host_port}->{container_port}")
    return sorted(published)


def create_app(docker_client=None):
    app = Flask(__name__)
    client = docker_client
    project = os.getenv("DASHBOARD_PROJECT", "mclsaat-legacy")

    def get_client():
        nonlocal client
        if client is None:
            import docker

            client = docker.from_env()
        return client

    def list_containers():
        containers = get_client().containers.list(all=True)
        if not project:
            return containers
        return [
            container
            for container in containers
            if container.labels.get("com.docker.compose.project") == project
        ]

    @app.get("/")
    def index():
        return render_template("index.html", project=project)

    @app.get("/healthz")
    def healthz():
        try:
            get_client().ping()
        except Exception as exc:
            return jsonify({"status": "unhealthy", "error": str(exc)}), 503
        return jsonify({"status": "ok"})

    @app.get("/api/containers")
    def containers():
        try:
            summaries = [_container_summary(container) for container in list_containers()]
        except Exception as exc:
            return jsonify({"error": f"Unable to read Docker state: {exc}"}), 503
        return jsonify({"project": project, "containers": summaries})

    return app


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
