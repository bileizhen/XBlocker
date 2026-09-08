"""Execute the inspected OEM automatic/manual card selection DEX method with fixtures.
This verifies the selection seam, not the device's complete rendering pipeline.
"""
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
import argparse, hashlib, pathlib, zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=pathlib.Path, help='ColorOS 16.1 SystemUIPlugin.apk captured from the test device')
parser.add_argument('--baseline', action='store_true', help='Assert collapsed behavior without the hook (expected failure)')
args = parser.parse_args()
assert hashlib.sha256(args.apk.read_bytes()).hexdigest() == 'c68bf570b8580690ab2761a73db6b5b23315b4f4add89e42bfe25baa45d72b74', 'Different plugin: re-inspect its decision path before replaying'
with zipfile.ZipFile(args.apk) as z:
    method = next(m for n in z.namelist() if n.endswith('.dex')
                  for c in DEX(z.read(n)).get_classes() if c.get_name() == 'Lk5/j;'
                  for m in c.get_methods() if m.get_name() == 'j')
code = method.get_code()
assert code.get_registers_size() == 15
instructions = {}
offset = 0
for ins in method.get_instructions():
    instructions[offset] = ins
    offset += ins.get_length()

class Cursor:
    def __init__(self, items): self.items, self.i = items, 0
    def has_next(self): return self.i < len(self.items)
    def next(self):
        item = self.items[self.i]
        self.i += 1
        return item

def select(manual, forced):
    candidate = {'u': 0, 'a': 'xblocker', 'c': forced}
    regs = {13: {'manual': manual, 'g': 'CARD_STATE_MANUAL' if manual else 'CARD_STATE_AUTO', 'e': []}, 14: [candidate]}
    pc, result = 0, None
    for step in range(2000):
        ins = instructions[pc]
        op = ins.get_name()
        operands = ins.get_operands()
        values = [o[1] for o in operands]
        nxt = pc + ins.get_length()
        out = ins.get_output()
        if op.startswith('const-string'):
            regs[values[0]] = operands[-1][2]
        elif op.startswith('const/'):
            regs[values[0]] = values[1]
        elif op == 'new-instance':
            assert 'ArrayList' in out
            regs[values[0]] = []
        elif op.startswith('move-result'):
            regs[values[0]] = result
        elif op.startswith('move'):
            regs[values[0]] = regs[values[1]]
        elif op == 'sget-object':
            assert 'Lo5/n;->e' in out
            regs[values[0]] = 4
        elif op.startswith('iget'):
            field = operands[-1][2].split('->')[1].split(' ')[0]
            regs[values[0]] = regs[values[1]][field]
        elif op == 'instance-of':
            assert 'Collection' in out
            regs[values[0]] = isinstance(regs[values[1]], list)
        elif op == 'check-cast': pass
        elif op == 'add-int/lit8':
            regs[values[0]] = regs[values[1]] + values[2]
        elif op.startswith('invoke'):
            args = [regs[v] for v in values[:-1]]
            target = operands[-1][2]
            if '-><init>' in target: result = None
            elif 'Lk5/j;->p()' in target: result = args[0]['manual']
            elif '->iterator()' in target: result = Cursor(args[0])
            elif '->hasNext()' in target: result = args[0].has_next()
            elif '->next()' in target: result = args[0].next()
            elif 'Lo5/n;->a(' in target: result = args[0] & args[1]
            elif '->areEqual(' in target: result = args[0] == args[1]
            elif '->add(' in target: args[0].append(args[1]); result = True
            elif '->isEmpty()' in target: result = len(args[0]) == 0
            elif '->clear()' in target: args[0].clear(); result = None
            else: raise AssertionError(target)
        elif op.startswith('if-'):
            a = regs[values[0]]
            if op.endswith('z'):
                take = {'if-eqz': not a, 'if-nez': bool(a), 'if-ltz': a < 0}[op]
            else:
                b = regs[values[1]]
                take = {'if-ge': a >= b}[op]
            if take: nxt = pc + values[-1] * 2
        elif op.startswith('goto'):
            nxt = pc + values[0] * 2
        elif op == 'return-object': return regs[values[0]]
        else: raise AssertionError((op, out))
        pc = nxt
    raise AssertionError('DEX replay did not terminate')

assert len(select(False, True)) == 1, 'Baseline must reproduce an automatic expanded card'
assert select(False, args.baseline) == [], 'Suppressing automatic reminder must exclude the expanded card'
assert len(select(True, False)) == 1, 'Manual expansion must still include the card'
assert len(select(False, True)) == 1, 'Other notification reminders must remain unaffected'
print('PASS: actual ColorOS k5.j.j DEX selection: baseline auto=1, fixed auto=0, manual=1, other auto=1')

