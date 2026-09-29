import struct,sys
def load(path):
    d=open(path,'rb').read();o=0;pk=[]
    while o<len(d):
        t,l=struct.unpack('<II',d[o:o+8]);b=d[o+8:o+l-4]
        if t==6:
            cl=struct.unpack('<I',b[12:16])[0];pk.append(b[20:20+cl])
        o+=l
    R=[]
    for i,p in enumerate(pk):
        hl,irp,st,fn,info,bus,dev,ep,tr,dl=struct.unpack('<HQIHBHHBBI',p[:27])
        R.append(dict(i=i,info=info,dev=dev,ep=ep,tr=tr,dl=dl,data=p[hl:]))
    return R
def job(path):
    R=load(path)
    out=b''
    for r in R:
        if r['tr']==3 and r['ep']==1 and not (r['info']&1) and r['data']: out+=r['data']
    return out
def parse(b):
    o=0;recs=[]
    while o<len(b):
        assert b[o:o+2]==b'\xcd\xca',(o,b[o:o+20].hex())
        fl=struct.unpack('>H',b[o+2:o+4])[0];cmd,ver,pl=struct.unpack('>HHH',b[o+4:o+10])
        recs.append((o,fl,cmd,b[o+20:o+20+pl]));o+=20+pl
    return recs
if __name__=='__main__':
    b=job(sys.argv[1]);open(sys.argv[2],'wb').write(b);print(len(b))
    for o,fl,cmd,p in parse(b):
        if cmd==0x1a: print(o,hex(fl),hex(cmd),len(p),p[:40].hex(' '))
