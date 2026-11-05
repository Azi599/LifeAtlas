FROM python:3.12-slim
WORKDIR /srv
COPY service.py /srv/service.py
RUN mkdir /data && useradd --uid 10001 --no-create-home atlas && chown atlas:atlas /data
USER atlas
ENV ATLAS_JOBS_DB=/data/jobs.sqlite3
EXPOSE 8080
CMD ["python", "/srv/service.py"]
