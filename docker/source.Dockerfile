FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONUTF8=1
COPY python/source/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY python/source ./source
EXPOSE 8002
CMD ["python", "-m", "uvicorn", "source.server:app", "--host", "0.0.0.0", "--port", "8002"]
