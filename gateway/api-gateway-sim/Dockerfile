FROM nginx:1.27-alpine
COPY gateway/api-gateway-sim/nginx.conf /etc/nginx/nginx.conf
COPY certs/api-gateway-sim /certs/api-gateway-sim
COPY certs/ca /certs/ca
EXPOSE 8443
