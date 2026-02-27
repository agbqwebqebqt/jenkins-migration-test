const express = require('express');
const app = express();
const port = process.env.PORT || 3000;

app.get('/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime() });
});

app.get('/', (req, res) => {
  res.send('<h1>Web Frontend</h1><p>Sample app for migration testing</p>');
});

app.listen(port, () => {
  console.log(`Server running on port ${port}`);
});
