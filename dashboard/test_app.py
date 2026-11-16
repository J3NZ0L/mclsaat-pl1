from unittest.mock import Mock

from app import create_app


def container(name="catalog-service", status="running", health="healthy"):
    value = Mock()
    value.name = f"/{name}"
    value.short_id = "abc123"
    value.status = status
    value.image.tags = ["example/catalog:latest"]
    value.labels = {"com.docker.compose.project": "test-project", "com.docker.compose.service": name}
    value.attrs = {
        "Config": {"Image": "example/catalog:latest", "Labels": value.labels},
        "State": {"Status": status, "Health": {"Status": health}, "StartedAt": "2026-01-01T10:00:00Z"},
        "NetworkSettings": {"Ports": {"8081/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8081"}]}},
    }
    return value


def test_container_api_returns_compose_state(monkeypatch):
    monkeypatch.setenv("DASHBOARD_PROJECT", "test-project")
    docker = Mock()
    docker.containers.list.return_value = [container()]
    response = create_app(docker).test_client().get("/api/containers")

    assert response.status_code == 200
    assert response.json["containers"][0]["service"] == "catalog-service"
    assert response.json["containers"][0]["health"] == "healthy"
    assert response.json["containers"][0]["ports"] == ["0.0.0.0:8081->8081/tcp"]


def test_container_api_filters_other_compose_projects(monkeypatch):
    monkeypatch.setenv("DASHBOARD_PROJECT", "test-project")
    docker = Mock()
    docker.containers.list.return_value = [container(), container("other")]
    docker.containers.list.return_value[1].labels["com.docker.compose.project"] = "other-project"

    response = create_app(docker).test_client().get("/api/containers")

    assert [item["service"] for item in response.json["containers"]] == ["catalog-service"]


def test_container_api_surfaces_docker_errors(monkeypatch):
    monkeypatch.setenv("DASHBOARD_PROJECT", "test-project")
    docker = Mock()
    docker.containers.list.side_effect = RuntimeError("socket unavailable")

    response = create_app(docker).test_client().get("/api/containers")

    assert response.status_code == 503
    assert "socket unavailable" in response.json["error"]
