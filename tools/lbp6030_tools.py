"""استخراج مهام LBP6030 من التقاطات USBPcap والتحقق من نموذج النقل.
الاستخدام:  python3 lbp6030_tools.py test1.pcapng [test2.pcapng ...]
يكتب app/src/main/assets/lbp6030_testN.bin ويتحقق أن Lbp6030Transport (نموذج بايثون مطابق) يُنتج
نفس بايتات OUT الملتقطة (باستثناء عدد استعلامات الحالة)."""
import struct, sys, re, os

def blocks(fn):
    d = open(fn, 'rb').read(); o = 0
    while o < len(d):
        t, l = struct.unpack_from('<II', d, o)
        yield t, d[o+8:o+l-4]; o += l

def usb_events(fn, dev=None):
    """يعيد (اتجاه, بيانات) لنقلات bulk على الطابعة (VID 04a9 PID 2795)."""
    devs = {}; cur = []
    pk = []
    for t, b in blocks(fn):
        if t != 6: continue
        cl = struct.unpack_from('<I', b, 12)[0]; p = b[20:20+cl]
        hl = struct.unpack_from('<H', p, 0)[0]
        status, func, info, bus, d, ep, xfer, dlen = struct.unpack_from('<IHBHHBBI', p, 10)
        pk.append((d, ep, xfer, info, p[hl:]))
    for d, ep, xfer, info, data in pk:  # device descriptor: 12 01 ... a9 04 95 27
        if xfer == 2 and len(data) == 18 and data[:2] == b'\x12\x01' and data[8:12] == bytes.fromhex('a9049527'):
            dev = d
    ev = []
    for d, ep, xfer, info, data in pk:
        if d != dev or xfer != 3 or not data: continue
        if ep == 0x01 and not (info & 1): ev.append(('OUT', data))
        if ep == 0x82 and (info & 1): ev.append(('IN', data))
    return ev

def split(ev):
    """OUT مقسّمة إلى إطارات (ترويسة 6 بايت + حمولة)."""
    outs = [d for t, d in ev if t == 'OUT']; k = 0; frames = []
    while k < len(outs):
        h = outs[k]; assert len(h) == 6
        typ, ln, fl = struct.unpack('>HHH', h)
        pl = outs[k+1] if ln > 6 else b''; assert len(pl) == ln - 6
        frames.append((typ, fl, pl)); k += 2 if ln > 6 else 1
    return frames

def job_of(frames):
    return b''.join(p for t, f, p in frames if t == 0x0110)

# ---- نموذج بايثون مطابق لـ Lbp6030Transport.kt ----
CHUNK = 8186
def poll_frame(seq):
    return (0x0220, 0x0102, bytes.fromhex('cd ca 10 04 00 1d'.replace(' ', '')) + struct.pack('>H', seq) + bytes.fromhex('0008 00000000000000000000 0000025900 0c0101'.replace(' ', '')))
def model(job, polls_pre=4, polls_post=0, seq0=0x29):
    F = []; seq = seq0
    for _ in range(polls_pre): F.append(poll_frame(seq)); seq += 1
    F.append((0x0000, 0x0100, bytes.fromhex('01 01 10 ff ff ff ff ff ff'.replace(' ', ''))))
    for i in range(0, len(job), CHUNK): F.append((0x0110, 0x0100, job[i:i+CHUNK]))
    F.append((0x0000, 0x0100, bytes.fromhex('020110')))
    for _ in range(polls_post): F.append(poll_frame(seq)); seq += 1
    return F

if __name__ == '__main__':
    out = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets')
    for fn in sys.argv[1:]:
        n = int(re.search(r'(\d+)', os.path.basename(fn)).group(1))
        fr = split(usb_events(fn)); job = job_of(fr)
        open(os.path.join(out, 'lbp6030_test%d.bin' % n), 'wb').write(job)
        non_poll = [f for f in fr if not (f[0] == 0x0220)]
        polls = [f for f in fr if f[0] == 0x0220]
        first_seq = struct.unpack('>H', polls[0][2][6:8])[0]
        m = [f for f in model(job, 0, 0) ]
        assert m == non_poll, 'model mismatch'
        assert all(p[2][:6] + p[2][8:] == poll_frame(0)[2][:6] + poll_frame(0)[2][8:] for p in polls)
        print(fn, 'job', len(job), 'bytes;', len(polls), 'polls; model OK')
