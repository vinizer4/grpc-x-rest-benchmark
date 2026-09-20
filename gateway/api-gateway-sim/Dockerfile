FROM nginx:1.27-alpine
RUN apk add --no-cache iproute2
COPY gateway/api-gateway-sim/nginx.conf /etc/nginx/nginx.conf
COPY certs/api-gateway-sim /certs/api-gateway-sim
COPY certs/ca /certs/ca
EXPOSE 8443
