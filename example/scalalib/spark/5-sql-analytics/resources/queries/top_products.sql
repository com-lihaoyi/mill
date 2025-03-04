WITH product_sales AS (
  SELECT product_id, product_name, SUM(amount) AS total_sales
  FROM sales
  GROUP BY product_id, product_name
)
SELECT product_id, product_name, total_sales,
       RANK() OVER (ORDER BY total_sales DESC) AS sales_rank
FROM product_sales
ORDER BY sales_rank