import base64, hmac, hashlib, struct, time, sys
g = base64.b32decode(sys.argv[1].replace(' ','').upper() + '=' * ((8 - len(sys.argv[1].replace(' ',''))%8)%8))
z = int(time.time()) // 30
h = hmac.new(g, struct.pack('>Q', z), hashlib.sha1).digest()
o = h[-1] & 0x0F
print('%06d' % ((struct.unpack('>I', h[o:o+4])[0] & 0x7FFFFFFF) % 1000000))
