FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY simulator/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY simulator/ .
RUN useradd --system --uid 10001 app
USER app
EXPOSE 8100
CMD ["python", "-m", "intellitwin_sim.run"]
