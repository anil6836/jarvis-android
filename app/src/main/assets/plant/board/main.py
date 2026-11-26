# Jarvis plant-watering board (Sir's own). The pump pin goes low first, before anything else.
from machine import Pin
Pin(26, Pin.OUT, value=0)
import time
print("jarvis-plant: ctrl-c now to update")  # (Jarvis waits for this line, then stops here over USB)
time.sleep(2)
import plant
plant.run()
