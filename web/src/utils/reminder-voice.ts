/**
 * 语音提醒（14 号 §5.7）：TTS 播报 + 首次交互预热。
 * 纯浏览器 API（speechSynthesis），零依赖、零音频资源文件。
 */

/** 浏览器是否支持语音合成（不支持则全部静默降级） */
function ttsSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'speechSynthesis' in window &&
    typeof SpeechSynthesisUtterance !== 'undefined'
  );
}

/** 播放一句文本（先 cancel 防止排队堆积） */
function speak(text: string, volume = 1): void {
  if (!ttsSupported()) return;
  try {
    // cancel 清掉未播完的队列，新提醒立即发声（连续来消息时不积压）
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    utterance.volume = volume;
    window.speechSynthesis.speak(utterance);
  } catch {
    // 播报失败静默降级，绝不影响消息提醒主链路
  }
}

/** 页面会话内监听器只绑定一次（BasicLayout 登出再登录会重复调用；页面刷新时模块重置、天然失效重绑） */
let preheatBound = false;

/**
 * 首次交互预热（14 号 §5.7，勿删）：
 * speechSynthesis 受浏览器 autoplay 策略门控——Safari 必然门控、Chrome 存在边缘场景，
 * 不能假设「无需解锁」。登录后监听全局首次 click/keydown（一次性），播一句空格占位
 * utterance（volume=0，无感；空字符串在部分浏览器会被忽略，故用空格），此后轮询播报可靠。
 */
export function setupVoicePreheat(): void {
  if (!ttsSupported() || preheatBound) return;
  preheatBound = true;
  const unlock = () => {
    speak(' ', 0);
    window.removeEventListener('click', unlock);
    window.removeEventListener('keydown', unlock);
  };
  window.addEventListener('click', unlock);
  window.addEventListener('keydown', unlock);
}

/**
 * 播报未读增量（14 号 §5.7）：
 * 传入本轮各来源的增量（>0），文案「闲鱼新增 3 条未读」；多来源用顿号拼接；
 * 单次触发仅播报一次（调用方保证每轮最多调一次）。
 */
export function playReminderVoice(increases: { label: string; delta: number }[]): void {
  if (increases.length === 0) return;
  const text = increases.map((i) => `${i.label}新增 ${i.delta} 条未读`).join('、');
  speak(text);
}

/** 手动触发预热（顶栏开关开启语音时调用，开关点击本身即用户交互）；空格占位同 setupVoicePreheat */
export function preheatVoiceNow(): void {
  speak(' ', 0);
}
