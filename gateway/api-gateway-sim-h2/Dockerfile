FROM nginx:1.27-alpine
RUN apk add --no-cache iproute2
COPY gateway/api-gateway-sim-h2/nginx.conf /etc/nginx/nginx.conf
COPY certs/api-gateway-sim-h2 /certs/api-gateway-sim-h2
COPY certs/ca /certs/ca
EXPOSE 8443
