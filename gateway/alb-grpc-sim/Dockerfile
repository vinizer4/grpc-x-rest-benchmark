FROM nginx:1.27-alpine
COPY gateway/alb-grpc-sim/nginx.conf /etc/nginx/nginx.conf
COPY certs/alb-grpc-sim /certs/alb-grpc-sim
COPY certs/ca /certs/ca
EXPOSE 8443
