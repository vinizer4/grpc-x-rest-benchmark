FROM nginx:1.27-alpine
RUN apk add --no-cache iproute2
COPY gateway/edge-h3-sim/nginx.conf /etc/nginx/nginx.conf
COPY certs/edge-h3-sim /certs/edge-h3-sim
COPY certs/ca /certs/ca
EXPOSE 8443
