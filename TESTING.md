# How to test MukhsApp (offline, phone to phone)

Use **two phones** with the same APK. Turn on Bluetooth, Wi-Fi and Location (Android needs them for finding each other; no internet or SIM data is used).
Allow every permission the app asks for (Nearby devices / Location, Microphone, Contacts, Notifications).
Put each phone's number in the other's Contacts (the app only shows people in your contacts).

1. **Find each other** - open the app on both, keep them 1-2 m apart. Within ~10-30 s the other person shows as nearby/online.
2. **Text** - send a message each way. Check the ticks: one = sent, two = arrived.
3. **Queued text** - on phone B close the app and move out of range. On A send 3 messages (they wait with a clock). Bring B back and open it: the messages arrive by themselves.
4. **Voice note** - hold the mic, record 5 s, release. Play it on the other phone. Try one while B is out of range, then come back.
5. **Voice call** - tap the phone icon in the chat. Talk both ways; try speaker on/off and mute. Hang up from either side.
6. **Range test** - walk apart in open ground (line of sight), sending a text every 20 m. Write down the distance where messages stop and where a call breaks up. Phone Bluetooth / Wi-Fi Direct reaches tens of metres, up to ~100 m outdoors; walls cut this a lot.

Send me: phone models, Android versions, and for any failure what you did and what you saw.
