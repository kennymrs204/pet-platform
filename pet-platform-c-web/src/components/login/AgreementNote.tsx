/** 协议说明。协议/隐私政策页尚无后端支持，链接为占位（阻止跳转，仅保留可点击语义）。 */
export default function AgreementNote() {
  return (
    <p className="text-center text-xs leading-relaxed text-muted">
      登录即代表你同意
      <a
        href="#user-agreement"
        onClick={(e) => e.preventDefault()}
        className="mx-1 text-navy underline decoration-navy/30 underline-offset-2 transition hover:decoration-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25 rounded-sm"
      >
        《用户协议》
      </a>
      和
      <a
        href="#privacy-policy"
        onClick={(e) => e.preventDefault()}
        className="mx-1 text-navy underline decoration-navy/30 underline-offset-2 transition hover:decoration-navy focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-navy/25 rounded-sm"
      >
        《隐私政策》
      </a>
    </p>
  )
}
