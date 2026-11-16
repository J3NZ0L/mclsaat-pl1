# Container dashboard

This is a deliberately isolated Flask application for observing the `mclsaat-legacy` Docker Compose
project. It is read-only: the Docker socket is mounted only to query container metadata and health
state, and no container actions are exposed.

## Run locally

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
flask --app app run --port 8000
```

Set `DASHBOARD_PROJECT` to change the Compose project filter. Set it to an empty value to display all
containers visible through the Docker socket.

## Tests

```bash
pip install -r requirements.txt pytest
pytest -q
```
