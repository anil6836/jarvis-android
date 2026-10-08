#!/usr/bin/env python3
"""Writes the Jarvis watch face (Watch Face Format, res/raw/watchface.xml).

W1: a HUD face: the theme's ring with ticks, a glowing core behind the time, the watch battery (left arc) and steps
(right arc) as gauges, heart rate, the date, and two slots that show Jarvis's next duty and the weather (the Jarvis
watch app's complications; any other can be chosen). W6: always-on dims to the time, the ring and the slots' text.
W5: three colour themes (the phone's: blue + gold, ice blue, golden amber), chosen in the face's Customize.
Drawn shapes only (no suit, helmet or logo). Run: python3 face/tools/make_face.py
"""
import math, os

C, R = 225, 225
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'res', 'raw', 'watchface.xml')
RING, MARK, TEXT = '[CONFIGURATION.themeColor.0]', '[CONFIGURATION.themeColor.1]', '[CONFIGURATION.themeColor.2]'

def ticks():
    out = []
    for i in range(60):
        a = math.radians(i * 6)
        big = i % 5 == 0
        r1, r2 = (196 if big else 203), 211
        x1, y1 = C + r1 * math.sin(a), C - r1 * math.cos(a)
        x2, y2 = C + r2 * math.sin(a), C - r2 * math.cos(a)
        out.append('      <Line startX="%.1f" startY="%.1f" endX="%.1f" endY="%.1f"><Stroke color="%s" thickness="%s" cap="ROUND"/></Line>'
                   % (x1, y1, x2, y2, MARK if big else RING, '3' if big else '1.5'))
    return '\n'.join(out)

def arc(name, start, sweep, value_expr, color, width=14):
    # track (dim) + value arc
    d = 2 * 182
    return f'''    <PartDraw x="0" y="0" width="450" height="450" name="{name}Track" alpha="60">
      <Arc centerX="{C}" centerY="{C}" width="{d}" height="{d}" startAngle="{start}" endAngle="{start + sweep}">
        <Stroke color="{color}" thickness="{width}" cap="ROUND"/>
      </Arc>
      <Variant mode="AMBIENT" target="alpha" value="0"/>
    </PartDraw>
    <PartDraw x="0" y="0" width="450" height="450" name="{name}">
      <Arc centerX="{C}" centerY="{C}" width="{d}" height="{d}" startAngle="{start}" endAngle="{start + 1}">
        <Stroke color="{color}" thickness="{width}" cap="ROUND"/>
        <Transform target="endAngle" value="{start} + {sweep} * clamp({value_expr}, 0, 100) / 100"/>
      </Arc>
      <Variant mode="AMBIENT" target="alpha" value="0"/>
    </PartDraw>'''

def text(name, x, y, w, h, size, color, template, params, ambient_hide=True, align='CENTER'):
    p = ''.join('<Parameter expression="%s"/>' % e for e in params)
    body = '<Template>%s%s</Template>' % (template, p) if params else template
    v = '\n      <Variant mode="AMBIENT" target="alpha" value="0"/>' if ambient_hide else ''
    return f'''    <PartText x="{x}" y="{y}" width="{w}" height="{h}" name="{name}">{v}
      <Text align="{align}" ellipsis="TRUE">
        <Font family="SYNC_TO_DEVICE" size="{size}" color="{color}" weight="NORMAL">
          {body}
        </Font>
      </Text>
    </PartText>'''

def slot(sid, name, x, y, provider, label):
    return f'''    <ComplicationSlot x="{x}" y="{y}" width="110" height="70" slotId="{sid}" supportedTypes="SHORT_TEXT" displayName="@string/{label}" isCustomizable="TRUE">
      <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="SHORT_TEXT" primaryProvider="com.anil.jarvis/com.anil.jarvis.watch.{provider}" primaryProviderType="SHORT_TEXT"/>
      <BoundingBox x="0" y="0" width="110" height="70"/>
      <Complication type="SHORT_TEXT">
        <PartText x="0" y="2" width="110" height="26" name="{name}Title">
          <Variant mode="AMBIENT" target="alpha" value="0"/>
          <Text align="CENTER" ellipsis="TRUE">
            <Font family="SYNC_TO_DEVICE" size="17" color="{MARK}" weight="NORMAL"><Template>%s<Parameter expression="[COMPLICATION.TITLE]"/></Template></Font>
          </Text>
        </PartText>
        <PartText x="0" y="28" width="110" height="38" name="{name}Text">
          <Text align="CENTER" ellipsis="TRUE">
            <Font family="SYNC_TO_DEVICE" size="28" color="{TEXT}" weight="BOLD"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font>
          </Text>
        </PartText>
      </Complication>
    </ComplicationSlot>'''

xml = f'''<WatchFace width="450" height="450" clipShape="CIRCLE">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="10:08:32"/>
  <UserConfigurations>
    <ColorConfiguration id="themeColor" displayName="@string/theme" defaultValue="mix">
      <ColorOption id="mix" displayName="@string/theme_mix" colors="#FF38BDF8 #FFF2B24C #FF74E4FF"/>
      <ColorOption id="blue" displayName="@string/theme_blue" colors="#FF22D3EE #FF74E4FF #FF74E4FF"/>
      <ColorOption id="gold" displayName="@string/theme_gold" colors="#FFF2B24C #FFFDE68A #FFFFD27A"/>
    </ColorConfiguration>
  </UserConfigurations>
  <Scene backgroundColor="#FF000000">
    <PartDraw x="0" y="0" width="450" height="450" name="ring">
      <Ellipse x="7" y="7" width="436" height="436"><Stroke color="{RING}" thickness="2"/></Ellipse>
{ticks()}
      <Variant mode="AMBIENT" target="alpha" value="110"/>
    </PartDraw>
    <PartDraw x="0" y="0" width="450" height="450" name="core">
      <Ellipse x="105" y="105" width="240" height="240">
        <Fill color="#FF000000">
          <RadialGradient centerX="225" centerY="225" radius="120" colors="#5574E4FF #2238BDF8 #00000000" positions="0 0.55 1"/>
        </Fill>
      </Ellipse>
      <Ellipse x="125" y="125" width="200" height="200"><Stroke color="{RING}" thickness="2"/></Ellipse>
      <Ellipse x="140" y="140" width="170" height="170"><Stroke color="{MARK}" thickness="1.5" dashIntervals="18 10"/></Ellipse>
      <Arc centerX="225" centerY="225" width="232" height="232" startAngle="320" endAngle="400"><Stroke color="{TEXT}" thickness="4" cap="ROUND"/></Arc>
      <Variant mode="AMBIENT" target="alpha" value="0"/>
    </PartDraw>
{arc("battery", 215, 110, "[BATTERY_PERCENT]", RING)}
{arc("steps", 35, 110, "[STEP_PERCENT]", MARK)}
{text("date", 125, 128, 200, 34, 22, MARK, "%s %s", ["[DAY_OF_WEEK_S]", "[DAY]"])}
    <DigitalClock x="100" y="168" width="250" height="96">
      <TimeText x="0" y="0" width="250" height="96" format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER">
        <Font family="SYNC_TO_DEVICE" size="76" color="#FFFFFFFF" weight="BOLD"/>
      </TimeText>
    </DigitalClock>
{text("heart", 160, 262, 130, 30, 20, TEXT, "♥ %s", ["[HEART_RATE]"])}
{text("battery", 56, 205, 64, 32, 19, RING, "⚡%s", ["[BATTERY_PERCENT]"])}
{text("steps", 328, 205, 70, 32, 19, MARK, "%s", ["[STEP_COUNT]"])}
{slot(1, "duty", 104, 318, "DutySource", "slot_duty")}
{slot(2, "weather", 236, 318, "WeatherSource", "slot_weather")}
  </Scene>
</WatchFace>
'''
with open(OUT, 'w', encoding='utf-8') as f:
    f.write(xml)
print('wrote', os.path.normpath(OUT))
