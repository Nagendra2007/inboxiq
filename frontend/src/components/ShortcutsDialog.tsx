import { Dialog } from './ui/Dialog';

const GROUPS: { title: string; items: { keys: string[]; label: string }[] }[] = [
  {
    title: 'Anywhere',
    items: [
      { keys: ['C'], label: 'Compose a new email' },
      { keys: ['?'], label: 'Show keyboard shortcuts' },
    ],
  },
  {
    title: 'Inbox',
    items: [
      { keys: ['/'], label: 'Search' },
      { keys: ['J'], label: 'Next email' },
      { keys: ['K'], label: 'Previous email' },
      { keys: ['X'], label: 'Select the open email' },
      { keys: ['E'], label: 'Archive the open email' },
      { keys: ['Esc'], label: 'Clear the selection, or close the email' },
    ],
  },
  {
    title: 'Writing',
    items: [{ keys: ['Ctrl', 'Enter'], label: 'Generate the AI draft' }],
  },
];

export function ShortcutsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onClose={onClose} title="Keyboard shortcuts" size="sm">
      <div className="space-y-5">
        {GROUPS.map((group) => (
          <section key={group.title}>
            <h3 className="eyebrow mb-2">{group.title}</h3>
            <ul className="divide-y divide-white/[0.05] rounded-xl border border-white/[0.06]">
              {group.items.map((item) => (
                <li key={item.label} className="flex items-center justify-between gap-4 px-3 py-2.5 text-sm text-white/70">
                  {item.label}
                  <span className="flex gap-1">
                    {item.keys.map((key) => (
                      <kbd key={key} className="kbd">
                        {key}
                      </kbd>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </Dialog>
  );
}
