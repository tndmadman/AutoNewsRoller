const { defineConfig } = require("@playwright/test");
module.exports = defineConfig({
  testDir: "./tests/browser",
  timeout: 65000,
  expect: { timeout: 10000 },
  retries: 1,
  workers: 2,
  use: { headless: true },
  projects: [{ name: "chromium", use: { browserName: "chromium" } },
             { name: "firefox", use: { browserName: "firefox" } }]
});
