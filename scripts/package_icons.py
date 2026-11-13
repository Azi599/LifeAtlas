"""Package the approved source art without redrawing its contents."""
from pathlib import Path
from PIL import Image
import shutil
root=Path(__file__).resolve().parents[1]
source=root/'assets/launcher-source.jpg'
if not source.exists():shutil.copyfile(root.parent/'upload/01-1000075089.png',source)
im=Image.open(source).convert('RGBA')
# Remove only the connected black outside matte; keep all art pixels intact.
pixels=im.load();w,h=im.size;seen=set();queue=[(0,0),(w-1,0),(0,h-1),(w-1,h-1)]
while queue:
 x,y=queue.pop()
 if (x,y) in seen or not (0<=x<w and 0<=y<h):continue
 seen.add((x,y));r,g,b,a=pixels[x,y]
 if max(r,g,b)>38:continue
 pixels[x,y]=(r,g,b,0)
 queue.extend(((x-1,y),(x+1,y),(x,y-1),(x,y+1)))
im.save(root/'app/src/main/res/drawable-nodpi/atlas_source.png')
canvas=Image.new('RGBA',(432,432));art=im.resize((288,288),Image.Resampling.LANCZOS);canvas.alpha_composite(art,(72,72));canvas.save(root/'app/src/main/res/drawable-nodpi/atlas_icon_foreground.png')
mono=canvas.copy();mp=mono.load()
for y in range(mono.height):
 for x in range(mono.width):
  r,g,b,a=mp[x,y];lum=.2126*r+.7152*g+.0722*b;alpha=int(max(0,min(1,(212-lum)/85))*a);mp[x,y]=(255,255,255,alpha)
mono.save(root/'app/src/main/res/drawable-nodpi/atlas_monochrome.png')
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
 folder=root/'app/src/main/res'/('mipmap-'+density);folder.mkdir(exist_ok=True)
 out=Image.new('RGBA',im.size,(237,228,250,255));out.alpha_composite(im);out.resize((size,size),Image.Resampling.LANCZOS).save(folder/'ic_launcher.png')
legacy=Image.new('RGBA',im.size,(237,228,250,255));legacy.alpha_composite(im);legacy.resize((512,512),Image.Resampling.LANCZOS).save(root/'assets/launcher-512.png')
