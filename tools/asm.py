import struct,sys;sys.path.insert(0,'/tmp')
from ext import parse
def rec(fl,cmd,payload):
    return b'\xcd\xca'+struct.pack('>HHHH',fl,cmd,1,len(payload))+b'\xff\xff'+bytes(8)+payload
def lensec(d):
    n=len(d)
    return (b'\xa0'+bytes([n])+b'\x9c'+bytes([n])) if n<256 else (b'\xa4'+struct.pack('>H',n)+b'\x9d'+struct.pack('>H',n))
BIH=bytes.fromhex('00000100000012700000 11a4 0000008000000048'.replace(' ',''))
def band_record(idx,d,rows=256):
    if idx==0:
        d=BIH+d
        return rec(0x1002,0x1a,bytes.fromhex('0161e68001e50062e3851270 0100e8a10000e105d7'.replace(' ',''))+lensec(d)+d)
    return rec(0x1002,0x1a,bytes.fromhex('0162e3851270'.replace(' ',''))+struct.pack('>H',rows)+bytes.fromhex('e8a50000')+bytes([idx,0])+bytes.fromhex('e105d7')+lensec(d)+d)
def split(job):
    recs=parse(job);pre=suf=None;first=last=None
    for i,(o,fl,cmd,p) in enumerate(recs):
        if cmd==0x1a and p[:2] in (b'\x01\x61',b'\x01\x62'):
            if first is None:first=o
            last=o+20+len(p)
    return job[:first],job[last:]
if __name__=='__main__':
    from verify_caps import bands,unpack
    from jb import dec
    job=open('/tmp/job.bin','rb').read()
    pre,suf=split(job)
    # band 0 من سجل 0x61
    out=pre
    for o,fl,cmd,p in parse(job):
        if cmd==0x1a and p[:2]==b'\x01\x61':
            d=p[p.index(b'\xff\x02') - 0:] if False else None
    print(len(pre),len(suf))
