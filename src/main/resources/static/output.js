const Output = {
    panel: null,
    input: null,
    userScrolledUp: false,
    history: [],
    historyIndex: -1,
    historyMax: 200,

    init() {
        const container = document.getElementById('terminal-container');
        container.innerHTML = `
            <div id="output-panel"></div>
            <div id="cmd-bar">
                <span class="cmd-prompt">$</span>
                <input type="text" id="cmd-input" placeholder="Type command..." autocomplete="off" spellcheck="false">
            </div>
        `;

        this.panel = document.getElementById('output-panel');
        this.input = document.getElementById('cmd-input');

        this.panel.addEventListener('scroll', () => {
            const atBottom = this.panel.scrollTop + this.panel.clientHeight >=
                this.panel.scrollHeight - 20;
            this.userScrolledUp = !atBottom;
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
            }
        });

        this.panel.addEventListener('click', () => {
            if (this.userScrolledUp) {
                this.userScrolledUp = false;
                this._scrollToBottom();
            } else {
                this.input.focus();
            }
        });

        this.input.focus();
    },

    write(raw) {
        if (!this.panel) return;
        let text = raw
            .replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
            .replace(/\x1b\].*?(?:\x07|\x1b\\)/g, '')
            .replace(/\x1b[()][0-9AB]/g, '')
            .replace(/\r/g, '');
        if (!text) return;

        // Per-line filter for Claude Code UI noise
        const lines = text.split('\n');
        const filtered = [];
        for (const line of lines) {
            const t = line.trim();
            if (!t) { filtered.push(line); continue; }
            if (line.includes('⏵⏵')) continue;
            if (t === '? for shortcuts' || t === '? for shortcuts ') continue;
            if (t === '❯' || t === '❯ ') continue;
            // Pure box-drawing separator line
            if (/^[\s─═━▄▀█▌▐]*$/.test(t)) continue;
            filtered.push(line);
        }
        text = filtered.join('\n');
        if (!text) return;

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
