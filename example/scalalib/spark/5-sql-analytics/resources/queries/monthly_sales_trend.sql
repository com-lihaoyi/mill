SELECT 
  YEAR(date) AS year, 
  MONTH(date) AS month, 
  SUM(amount) AS monthly_sales,
  SUM(SUM(amount)) OVER (ORDER BY YEAR(date), MONTH(date)) AS cumulative_sales
FROM sales
GROUP BY YEAR(date), MONTH(date)
ORDER BY year, month