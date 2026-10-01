from qm import QMEnc
TM=[(-3,-1),(-2,-1),(-1,-1),(0,-1),(1,-1),(2,-1),(-1,0),(-2,0),(-3,0),(-4,0)]
SLNTP=0x166  # يطابق libjbig مع ترتيب TM أدناه (0x195 خاطئ لهذا الترتيب)
def encode_band(rows,w,l0=128,final=b'\xff\x03',strip=True,reset_lntp=True):
    h=len(rows);out=bytearray();e=QMEnc(out);zero=[0]*w;lntp=0
    ys=0
    while ys<h:
        ye=min(h,ys+l0);e.reset_coder()
        if reset_lntp: lntp=0
        start=len(out)
        for y in range(ys,ye):
            cur=rows[y];up=rows[y-1] if y>0 else zero;up2=rows[y-2] if y>1 else zero
            typical=1 if cur==up else 0
            e.encode(SLNTP,typical^lntp^1);lntp=typical
            if typical: continue
            for x in range(w):
                cx=0
                for i,(dx,dy) in enumerate(TM):
                    xx=x+dx
                    if 0<=xx<w:
                        r=up if dy==-1 else up2 if dy==-2 else cur
                        cx|=r[xx]<<i
                e.encode(cx,cur[x])
        e.flush()
        if strip:
            while len(out)>start and out[-1]==0: out.pop()
        out+= b'\xff\x02' if ye<h else final
        ys=ye
    return bytes(out)
