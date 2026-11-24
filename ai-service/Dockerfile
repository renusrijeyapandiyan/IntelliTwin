# build context: repository root (the trainer imports the simulator package)
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 MODEL_REGISTRY_DIR=/models
WORKDIR /app
COPY ai-service/requirements.txt ai-service/requirements.txt
COPY simulator/requirements.txt simulator/requirements.txt
RUN pip install --no-cache-dir -r ai-service/requirements.txt -r simulator/requirements.txt
COPY simulator /app/simulator
COPY shared /app/shared
COPY ai-service /app/ai-service
WORKDIR /app/ai-service
RUN useradd --system --uid 10001 app && mkdir -p /models && chown -R app /models /app
# bake the first model into the image: version-consistent with the installed scikit-learn, and it survives hosts with an ephemeral disk
RUN python -m training.train --seed 42 --cycles 24 --registry /models --activate && chown -R app /models
USER app
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --retries=30 --start-period=150s \
  CMD python -c "import json,urllib.request,sys; sys.exit(0 if json.load(urllib.request.urlopen('http://localhost:8000/health'))['status']=='ok' else 1)"
ENTRYPOINT ["/bin/sh", "docker-entrypoint.sh"]
