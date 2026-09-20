CREATE TABLE vendas (
    id_venda UUID PRIMARY KEY,
    id_produto BIGINT NOT NULL,
    nome_produto VARCHAR(255) NOT NULL,
    categoria VARCHAR(100) NOT NULL,
    quantidade INTEGER NOT NULL,
    valor_unitario NUMERIC(10, 2) NOT NULL,
    valor_total NUMERIC(12, 2) NOT NULL,
    data_venda TIMESTAMP NOT NULL,
    id_loja INTEGER NOT NULL,
    nome_loja VARCHAR(255) NOT NULL,
    regiao VARCHAR(100) NOT NULL,
    forma_pagamento VARCHAR(50) NOT NULL
);

CREATE INDEX idx_vendas_produto_data ON vendas (id_produto, data_venda);
CREATE INDEX idx_vendas_loja_data ON vendas (id_loja, data_venda);
