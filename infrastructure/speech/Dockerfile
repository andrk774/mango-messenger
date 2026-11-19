FROM python:3.12-slim
RUN pip install --no-cache-dir faster-whisper==1.2.1 av==16.0.1
RUN useradd --uid 10001 --create-home speech && mkdir /models && chown speech:speech /models
WORKDIR /app
COPY server.py /app/server.py
USER speech
ENV HF_HOME=/models HF_HUB_DISABLE_TELEMETRY=1 HF_HUB_DISABLE_XET=1
EXPOSE 8090
CMD ["python", "server.py"]
