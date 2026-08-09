-- Seed the 4 rooms based on the house layout:
-- 2 bedrooms sharing one AC (ESP32 #1), 1 bedroom (ESP32 #2), 1 living room (ESP32 #2), panel (ESP32 #3)
INSERT INTO rooms (id, name, mqtt_topic) VALUES
    (gen_random_uuid(), 'Kamar 1', 'smart-home/presence/room1'),
    (gen_random_uuid(), 'Kamar 2', 'smart-home/presence/room2'),
    (gen_random_uuid(), 'Kamar 3', 'smart-home/presence/room3'),
    (gen_random_uuid(), 'Ruang Tamu', 'smart-home/presence/living');
