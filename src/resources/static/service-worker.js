// service-worker.js

self.addEventListener('push', event => {
  const data = event.data.json();
  console.log('Push notification received:', data);

  const title = data.title;
  const options = {
    body: data.body,
    icon: 'https://placehold.co/192x192/4f46e5/ffffff?text=A',
    badge: 'https://placehold.co/96x96/4f46e5/ffffff?text=A',
    // You can add sound here, but it requires user interaction to play
    // sound: '/sounds/default.mp3'
  };

  event.waitUntil(
    self.registration.showNotification(title, options)
  );
});

self.addEventListener('notificationclick', event => {
  console.log('Notification clicked.');
  event.notification.close();
  
  // This can be configured to open the app or a specific URL
  event.waitUntil(
    clients.openWindow('/alarm_app.html')
  );
});
