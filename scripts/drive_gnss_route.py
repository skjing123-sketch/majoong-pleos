#!/usr/bin/env python3
"""Replay a synthetic straight path between the public sample points using GNSS only.

The path is a GNSS integration test, not a road-geometry assertion. It invokes no
NaviHelper methods or arrival callbacks. Start a real navigation route separately
and keep the navigation app foreground. Console success never verifies arrival.
"""
import argparse,json,math,time
from datetime import datetime,timezone
from pathlib import Path
from feed_gnss import POINTS,ConsoleInputError,default_adb,nmea_sentences,send_console_input
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',default='emulator-5562')
p.add_argument('--adb',type=Path,default=default_adb())
p.add_argument('--duration',type=int,default=120)
p.add_argument('--hold-start',type=int,default=12)
p.add_argument('--hold-end',type=int,default=20)
p.add_argument('--sync-auto',action='store_true',help='Synchronize the emulator passive fix with each manual NMEA point.')
a=p.parse_args()
if not a.serial.startswith('emulator-') or not a.serial[9:].isdigit():p.error('Emulator serial required.')
if not (30<=a.duration<=600 and 0<=a.hold_start<=60 and 0<=a.hold_end<=60):p.error('Use duration 30..600 and hold durations 0..60 seconds.')
adb=a.adb
start,end=POINTS['start'],POINTS['destination']
lat1,lon1=map(math.radians,start);lat2,lon2=map(math.radians,end)
h=math.sin((lat2-lat1)/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
distance=6371000*2*math.asin(math.sqrt(h))
bearing=(math.degrees(math.atan2(math.sin(lon2-lon1)*math.cos(lat2),math.cos(lat1)*math.sin(lat2)-math.sin(lat1)*math.cos(lat2)*math.cos(lon2-lon1)))+360)%360
speed=distance/a.duration/0.514444
schedule=[('start',0.0,0.0)]*a.hold_start
schedule += [('driving',i/a.duration,speed) for i in range(1,a.duration+1)]
schedule += [('destination',1.0,0.0)]*a.hold_end
print(json.dumps({'event':'replay_start','utc':datetime.now(timezone.utc).isoformat(),'durationSeconds':a.duration,'automaticPositionSynchronized':a.sync_auto}),flush=True)
origin=time.monotonic()
for index,(phase,t,knots) in enumerate(schedule):
 lat=start[0]+(end[0]-start[0])*t;lon=start[1]+(end[1]-start[1])*t
 try:
  if a.sync_auto:
   input_type='geo_fix'
   send_console_input(adb,a.serial,['fix',f'{lon:.8f}',f'{lat:.8f}','50','8',f'{knots:.3f}'])
  for input_type,sentence in zip(('GPGGA','GPRMC'),nmea_sentences(lat,lon,datetime.now(timezone.utc),knots,bearing)):
   send_console_input(adb,a.serial,['nmea',sentence])
 except ConsoleInputError as error:
  print(json.dumps({'event':'replay_failed','utc':datetime.now(timezone.utc).isoformat(),
                    'sample':index+1,'total':len(schedule),'phase':phase,'progress':round(t,3),
                    'input':input_type,'errorCategory':error.category,'consoleAccepted':False,
                    'automaticPositionSynchronized':a.sync_auto,'arrivalVerified':False}),flush=True)
  raise SystemExit(1) from None
 if index%10==0 or index+1==len(schedule):print(json.dumps({'sample':index+1,'total':len(schedule),'phase':phase,'progress':round(t,3),'consoleAccepted':True,'automaticPositionSynchronized':a.sync_auto,'arrivalVerified':False}),flush=True)
 delay=origin+index+1-time.monotonic()
 if delay>0:time.sleep(delay)

print(json.dumps({'event':'replay_complete','utc':datetime.now(timezone.utc).isoformat(),'arrivalVerified':False}),flush=True)
