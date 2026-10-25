package io.seekflux.platform.agenteval;

import java.io.IOException;
import java.nio.file.*;
import static io.seekflux.platform.agenteval.Evaluation.*;

public final class ReportWriter {
    private ReportWriter() {}
    public static void write(Path directory, Report report) throws IOException {
        Files.createDirectories(directory);
        EvalJson.write(directory.resolve("report.json"), report);
        StringBuilder lines = new StringBuilder();
        report.results().forEach(r -> lines.append(EvalJson.compact(r)).append('\n'));
        Files.writeString(directory.resolve("cases.jsonl"), lines.toString());
        Files.writeString(directory.resolve("index.html"), html(report));
    }
    public static String html(Report r) {
        StringBuilder rows = new StringBuilder();
        for (CaseResult c : r.results()) {
            rows.append("<article data-status=\"").append(c.status()).append("\"><details><summary><span class=\"badge ")
                    .append(c.status()).append("\">").append(c.status()).append("</span><b>")
                    .append(escape(c.caseId())).append("</b><small>").append(c.tookMillis()).append(" ms</small></summary><div class=\"body\">");
            for (Finding f : c.findings()) rows.append("<p><span class=\"").append(f.status()).append("\">")
                    .append(f.status()).append("</span> <strong>").append(escape(f.id())).append("</strong><br>")
                    .append(escape(f.detail())).append("</p>");
            rows.append("<details><summary>查看原始证据 / Evidence</summary><pre>")
                    .append(escape(EvalJson.pretty(c.evidence()))).append("</pre></details></div></details></article>");
        }
        return """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Agent Eval · 评测报告</title><style>
                :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#f4f6f9;color:#182233;font:15px/1.6 system-ui,sans-serif}
                main{max-width:1080px;margin:auto;padding:48px 24px}header{border-top:4px solid #204be0;padding-top:20px}
                .eyebrow{color:#4262b1;letter-spacing:.16em;font-size:12px}h1{font-size:36px;line-height:1.2;margin:12px 0}
                .muted,small{color:#657084}code{overflow-wrap:anywhere;font-size:12px}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:30px 0}
                .card,article{background:white;border:1px solid #dce2eb;border-radius:12px}.card{padding:18px}.card b{display:block;font-size:30px}
                .PASS{color:#087650}.FAIL{color:#c44031}.ERROR{color:#9a5b00}.controls{display:flex;gap:12px;margin:25px 0}
                input,select{font:inherit;padding:10px;border:1px solid #cbd3df;border-radius:8px}input{flex:1;min-width:0}
                article{margin:10px 0}summary{cursor:pointer;padding:17px;display:flex;align-items:center;gap:16px}
                summary b{flex:1}.badge{font-size:12px;font-weight:700;min-width:50px}.body{padding:0 20px 20px;border-top:1px solid #edf0f5}
                .body p{font-size:13px;padding:10px 0;border-bottom:1px solid #edf0f5}pre{background:#111b2c;color:#dce6f6;padding:16px;border-radius:8px;overflow:auto;max-height:480px;font-size:12px}
                footer{margin-top:30px;font-size:13px;color:#657084}@media(max-width:600px){.cards{grid-template-columns:repeat(2,1fr)}h1{font-size:28px}summary{gap:8px}}
                </style><main><header><div class="eyebrow">SEEKFLUX / AGENT EVALUATION 1.0</div><h1>通用 Agent 评测报告</h1>
                """ + "<p>" + escape(r.suiteId()) + " · " + escape(r.suiteVersion()) + " / " + escape(r.targetVersion())
                + "</p><p class=\"muted\">" + escape(r.createdAt()) + " · " + escape(r.runId()) + "</p></header>"
                + "<div class=\"cards\">" + card("用例总数", r.results().size(), "")
                + card("PASS", r.count(Status.PASS), "PASS") + card("FAIL", r.count(Status.FAIL), "FAIL")
                + card("ERROR", r.count(Status.ERROR), "ERROR") + "</div>"
                + "<p class=\"muted\">PASS 表示符合该用例预期，预期拒绝或降级也可通过。完整事件、消息、Trace 和副作用快照可逐项展开。</p>"
                + "<div class=\"controls\"><input id=\"search\" aria-label=\"搜索用例\" placeholder=\"搜索用例 ID\"><select id=\"status\" aria-label=\"筛选状态\"><option>ALL</option><option>PASS</option><option>FAIL</option><option>ERROR</option></select></div>"
                + rows + "<footer>Suite SHA-256: <code>" + escape(r.suiteHash()) + "</code><br>Graders: "
                + escape(String.join(", ", r.graders())) + "<br>模型 usage 未测量时保留 null；此报告的通过率不代表真实模型任务成功率。</footer></main>"
                + """
                <script>
                const search=document.querySelector('#search'),status=document.querySelector('#status');
                function filter(){for(const row of document.querySelectorAll('article'))row.hidden=!(row.querySelector('b').textContent.toLowerCase().includes(search.value.toLowerCase())&&(status.value==='ALL'||row.dataset.status===status.value));}
                search.addEventListener('input',filter);status.addEventListener('change',filter);
                </script></html>
                """;
    }
    private static String card(String label, long value, String color) {
        return "<div class=\"card " + color + "\">" + label + "<b>" + value + "</b></div>";
    }
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}

