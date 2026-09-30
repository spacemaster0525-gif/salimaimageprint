import json
T=json.load(open('/tmp/qm.json'));LSZ=T['lsz'];NMPS=T['nmps'];NLPS=T['nlps'];SW=T['sw']
class QMEnc:
    def __init__(self,out=None):
        self.out=bytearray() if out is None else out
        self.st=[0]*4096; self.reset_coder()
    def reset_coder(self):
        self.c=0;self.a=0x10000;self.sc=0;self.ct=11;self.buf=-1
    def _o(self,b):
        self.out.append(b&0xff)
    def encode(self,cx,pix):
        st=self.st[cx]; idx=st&0x7f; lsz=LSZ[idx]
        ss=(pix<<7)^(st&0x80)
        self.a-=lsz
        if ss==0:
            if self.a&0xffff8000: return
            if self.a<lsz:
                self.c+=self.a; self.a=lsz
            self.st[cx]=(st&0x80)|NMPS[idx]
        else:
            if self.a>=lsz:
                self.c+=self.a; self.a=lsz
            self.st[cx]=((st&0x80)^(0x80 if SW[idx] else 0))|NLPS[idx]
        while True:
            self.a<<=1; self.c<<=1; self.ct-=1
            if self.ct==0:
                t=self.c>>19
                if t&~0xff:
                    if self.buf>=0:
                        self.buf+=1; self._o(self.buf)
                        if self.buf==0xff: self._o(0)
                    while self.sc: self._o(0); self.sc-=1
                    self.buf=t&0xff
                elif t==0xff:
                    self.sc+=1
                else:
                    if self.buf>=0: self._o(self.buf)
                    while self.sc: self._o(0xff); self._o(0); self.sc-=1
                    self.buf=t
                self.c&=0x7ffff; self.ct=8
            if self.a>=0x8000: break
    def flush(self):
        t=(self.a-1+self.c)&0xffff0000
        self.c = t+0x8000 if t<self.c else t
        self.c<<=self.ct
        if self.c&0xf8000000:
            if self.buf>=0:
                self._o(self.buf+1)
                if self.buf+1==0xff: self._o(0)
            if self.c&0x7fff800:
                while self.sc: self._o(0); self.sc-=1
        else:
            if self.buf>=0: self._o(self.buf)
            if self.c&0x7fff800:
                while self.sc: self._o(0xff); self._o(0); self.sc-=1
        if self.c&0x7fff800:
            b=(self.c>>19)&0xff; self._o(b)
            if b==0xff: self._o(0)
            if self.c&0x7f800:
                b=(self.c>>11)&0xff; self._o(b)
                if b==0xff: self._o(0)
