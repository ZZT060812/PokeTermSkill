const Output = {
    panel: null,
    input: null,
    permBar: null,
    userScrolledUp: false,
    history: [],
    historyIndex: -1,
    historyMax: 200,
    permActive: false,
    permBuffer: '',

    init() {
        const container = document.getElementById('terminal-container');
        container.innerHTML = `
            <div id="output-panel"></div>
            <div id="perm-bar">
                <span id="perm-text"></span>
                <div class="perm-buttons">
                    <button class="perm-btn perm-yes" data-action="yes">Yes</button>
                    <button class="perm-btn perm-always" data-action="always">Yes, don't ask</button>
                    <button class="perm-btn perm-no" data-action="no">No</button>
                </div>
            </div>
            <div id="cmd-bar">
                <span class="cmd-prompt">$</span>
                <input type="text" id="cmd-input" placeholder="Type command..." autocomplete="off" spellcheck="false">
                <button class="key-btn" data-key="Up" title="Arrow Up">▲</button>
                <button class="key-btn" data-key="Down" title="Arrow Down">▼</button>
                <button class="key-btn" data-key="Enter" title="Enter (raw)">↵</button>
                <button class="key-btn key-btn-esc" data-key="Escape" title="Escape">Esc</button>
                <button class="key-btn key-btn-cc" data-key="C-c" title="Ctrl+C">⏹</button>
            </div>
        `;

        this.panel = document.getElementById('output-panel');
        this.input = document.getElementById('cmd-input');
        this.permBar = document.getElementById('perm-bar');

        // Perm bar buttons
        document.querySelectorAll('.perm-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const action = btn.dataset.action;
                this._handlePermAction(action);
            });
        });

        this.panel.addEventListener('scroll', () => {
            const atBottom = this.panel.scrollTop + this.panel.clientHeight >=
                this.panel.scrollHeight - 20;
            this.userScrolledUp = !atBottom;
        });

        document.querySelectorAll('.key-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                App.send({ type: 'term.input', key: btn.dataset.key });
                this.input.focus();
            });
        });

        this.input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                const cmd = this.input.value;
                if (cmd.trim()) {
                    this._addLine('$ ' + cmd, 'cmd-echo');
                    App.send({ type: 'term.input', data: cmd });
                    this.history.push(cmd);
                    if (this.history.length > this.historyMax) this.history.shift();
                    this.historyIndex = -1;
                } else {
                    App.send({ type: 'term.input', key: 'Enter' });
                }
                this.input.value = '';
                this.userScrolledUp = false;
                this._scrollToBottom();
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                this._navHistory(-1);
            } else if (e.key === 'ArrowDown') {
                e.preventDefault();
                this._navHistory(1);
            } else if (e.key === 'Escape') {
                if (this.input.value) {
                    this.input.value = '';
                } else {
                    App.send({ type: 'term.input', key: 'Escape' });
                }
                // Also dismiss perm bar
                if (this.permActive) this._hidePermBar();
            }
        });

        // Tap output panel to focus input (but NOT on init — avoids mobile keyboard popup)
        this.panel.addEventListener('click', () => {
            if (this.userScrolledUp) {
                this.userScrolledUp = false;
                this._scrollToBottom();
            } else {
                this.input.focus();
            }
        });

        // Clear any stray value
        this.input.value = '';
    },

    // ---- Permission detection ----
    // Claude Code permission prompts contain ⏺ ... ? followed by options like ❯ Yes
    _PERM_RE: /⏺[^\n]*\?/,

    _detectPerm(text) {
        return this._PERM_RE.test(text);
    },

    _showPermBar(summary) {
        this.permBar.style.display = 'flex';
        this.permBar.classList.add('active');
        document.getElementById('perm-text').textContent = summary;
        this.permActive = true;
        // Shrink output panel to make room
        this.panel.style.bottom = (38 + this.permBar.offsetHeight) + 'px';
        this._scrollToBottom();
    },

    _hidePermBar() {
        this.permBar.style.display = 'none';
        this.permBar.classList.remove('active');
        this.permActive = false;
        this.permBuffer = '';
        this.panel.style.bottom = '38px';
    },

    _handlePermAction(action) {
        let keys;
        switch (action) {
            case 'yes':
                // Send Enter to select the highlighted (first) option
                keys = ['Enter'];
                break;
            case 'always':
                // Down then Enter to select second option
                keys = ['Down', 'Enter'];
                break;
            case 'no':
                // Down Down Enter to select third option
                keys = ['Down', 'Down', 'Enter'];
                break;
        }
        keys.forEach(k => App.send({ type: 'term.input', key: k }));
        this._hidePermBar();
    },

    // ---- Output ----

    write(raw) {
        if (!this.panel) return;
        let text = raw
            .replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
            .replace(/\x1b\].*?(?:\x07|\x1b\\)/g, '')
            .replace(/\x1b[()][0-9AB]/g, '')
            .replace(/\r/g, '');
        if (!text) return;

        // Permission detection — check BEFORE filtering out ⏺ lines
        if (this._detectPerm(text)) {
            this.permBuffer += text;
            const match = this.permBuffer.match(/⏺[^\n]*\?/);
            if (match) this._showPermBar(match[0].trim());
            return;
        }

        const lines = text.split('\n');
        const filtered = [];
        for (const line of lines) {
            const t = line.trim();
            if (!t) { filtered.push(line); continue; }
            if (line.includes('⏵⏵')) continue;
            if (t === '? for shortcuts' || t === '? for shortcuts ') continue;
            if (t === '❯' || t === '❯ ') continue;
            if (/^[\s─═━▄▀█▌▐]*$/.test(t)) continue;
            if (t === 'esc to interrupt' || t === 'esc to interrupt ') continue;
            filtered.push(line);
        }
        text = filtered.join('\n');
        if (!text) return;

        // Permission prompt detection
        if (this._detectPerm(text)) {
            this.permBuffer += text;
            // Extract summary: the line with ⏺ ... ?
            const match = this.permBuffer.match(/⏺[^\n]*\?/);
            if (match) {
                this._showPermBar(match[0].trim());
            }
            return; // don't pollute main output
        }

        // If perm was active but new output doesn't have perm prompt,
        // the permission was resolved — allow output through and hide bar
        if (this.permActive && !this._detectPerm(text)) {
            this._hidePermBar();
        }

        const last = this.panel.lastElementChild;
        if (last && last.classList.contains('output-line') && !last.classList.contains('cmd-echo')) {
            last.textContent += text;
        } else {
            const span = document.createElement('span');
            span.className = 'output-line';
            span.textContent = text;
            this.panel.appendChild(span);
        }

        if (!this.userScrolledUp) this._scrollToBottom();
        this._trimNodes();
    },

    _addLine(text, cls) {
        const span = document.createElement('span');
        span.className = 'output-line ' + (cls || '');
        span.textContent = text + '\n';
        this.panel.appendChild(span);
        this._scrollToBottom();
        this._trimNodes();
    },

    _scrollToBottom() {
        requestAnimationFrame(() => {
            this.panel.scrollTop = this.panel.scrollHeight;
        });
    },

    _trimNodes() {
        while (this.panel.children.length > 600) {
            this.panel.firstChild.remove();
        }
    },

    _navHistory(dir) {
        if (!this.history.length) return;
        if (this.historyIndex === -1) {
            this.historyIndex = dir === -1 ? this.history.length - 1 : 0;
        } else {
            this.historyIndex = Math.max(0,
                Math.min(this.history.length - 1, this.historyIndex + dir));
        }
        this.input.value = this.history[this.historyIndex] || '';
    },

    sendInit() {
        App.send({ type: 'term.init' });
    }
};
