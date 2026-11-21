-- Each service owns its own database. The notification service never reads the booking tables.
CREATE DATABASE notifications OWNER seatbooking;
