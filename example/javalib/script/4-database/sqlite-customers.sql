CREATE TABLE buyer
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          VARCHAR(256),
    date_of_birth DATE
);

CREATE TABLE product
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    kebab_case_name VARCHAR(256),
    name            VARCHAR(256),
    price           DECIMAL(20, 2)
);

CREATE TABLE shipping_info
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    buyer_id      INT,
    shipping_date DATE,
    FOREIGN KEY (buyer_id) REFERENCES buyer (id)
);

CREATE TABLE purchase
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    shipping_info_id INT,
    product_id       INT,
    count            INT,
    total            DECIMAL(20, 2),
    FOREIGN KEY (shipping_info_id) REFERENCES shipping_info (id),
    FOREIGN KEY (product_id) REFERENCES product (id)
);


INSERT INTO buyer (name, date_of_birth)
VALUES ('James Bond', '2001-02-03'),
       ('John Doe', '1923-11-12'),
       ('Li Haoyi', '1965-08-09');

INSERT INTO product (kebab_case_name, name, price)
VALUES ('face-mask', 'Face Mask', 8.88),
       ('guitar', 'Guitar', 300),
       ('socks', 'Socks', 3.14),
       ('skate-board', 'Skate Board', 123.45),
       ('camera', 'Camera', 1000.00),
       ('cookie', 'Cookie', 0.10);

INSERT INTO shipping_info (buyer_id, shipping_date)
VALUES (2, '2010-02-03'),
       (1, '2012-04-05'),
       (2, '2012-05-06');

INSERT INTO purchase (shipping_info_id, product_id, count, total)
VALUES (1, 1, 100, 888),
       (1, 2, 3, 900),
       (1, 3, 5, 15.7),
       (2, 4, 4, 493.8),
       (2, 5, 10, 10000.00),
       (3, 1, 5, 44.4),
       (3, 6, 13, 1.30);