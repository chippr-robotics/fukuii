def decode_item(b, i):
    p = b[i]
    if p < 0x80:
        return b[i:i+1], i+1
    if p < 0xb8:
        l = p - 0x80
        return b[i+1:i+1+l], i+1+l
    if p < 0xc0:
        ll = p - 0xb7
        l = int.from_bytes(b[i+1:i+1+ll],'big')
        s = i+1+ll
        return b[s:s+l], s+l
    if p < 0xf8:
        l = p - 0xc0
        end = i+1+l
        out=[]; j=i+1
        while j < end:
            it, j = decode_item(b, j)
            out.append(it)
        return out, end
    ll = p - 0xf7
    l = int.from_bytes(b[i+1:i+1+ll],'big')
    s = i+1+ll; end = s+l
    out=[]; j=s
    while j < end:
        it, j = decode_item(b, j)
        out.append(it)
    return out, end

def decode(b):
    v,i = decode_item(b,0)
    return v

def decode_stream(b):
    i=0; out=[]
    while i < len(b):
        v,i = decode_item(b,i)
        out.append(v)
    return out

def ii(x):
    return int.from_bytes(x,'big') if isinstance(x,(bytes,bytearray)) else None

def hx(x):
    return '0x'+x.hex()
def enc(x):
    if isinstance(x,int):
        x = b'' if x==0 else x.to_bytes((x.bit_length()+7)//8,'big')
    if isinstance(x,(bytes,bytearray)):
        x=bytes(x)
        if len(x)==1 and x[0]<0x80: return x
        if len(x)<56: return bytes([0x80+len(x)])+x
        l=len(x).to_bytes((len(x).bit_length()+7)//8,'big'); return bytes([0xb7+len(l)])+l+x
    p=b''.join(enc(i) for i in x)
    if len(p)<56: return bytes([0xc0+len(p)])+p
    l=len(p).to_bytes((len(p).bit_length()+7)//8,'big'); return bytes([0xf7+len(l)])+l+p
