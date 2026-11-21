// Developing on the computer: the media server's addresses go to the real server (same as the published page).
if (config.devServer) {
  config.devServer.port = 8080;
  config.devServer.open = false;
  config.devServer.proxy = [{
    context: ['/media', '/m/'],
    target: 'https://qeyf.159.195.147.140.sslip.io',
    changeOrigin: true,
    secure: true,
  }];
}
