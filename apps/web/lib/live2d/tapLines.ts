import { CHTHOLLY_EXPRESSION } from "@/lib/live2d/constants";

/** 珂朵莉点击语音：表情键名 */
export type ChthollyTapExpression = keyof typeof CHTHOLLY_EXPRESSION;

export type ChthollyTapLine = {
  id: string;
  sound: string;
  motionIndex: number;
  expression: ChthollyTapExpression;
  /** 日语原文，" / " 分隔多句 */
  textJa: string;
  textZh: string;
  durationSec: number;
};

/** 将台词转为展示用行（按 " / " 分段） */
export function formatTapLineJa(textJa: string): string[] {
  return textJa.split(" / ").map((s) => s.trim()).filter(Boolean);
}

export const CHTHOLLY_TAP_LINES: ChthollyTapLine[] = [
  {
    "id": "line-01",
    "sound": "motions/tap/tap-chtholly-line-01.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "あの　いろいろしてくれてありがとう / それと　いろいろ迷惑かけてごめんなさい",
    "textZh": "那个，谢谢你为我做了这么多。还有，给你添了很多麻烦，对不起。",
    "durationSec": 6.12
  },
  {
    "id": "line-02",
    "sound": "motions/tap/tap-chtholly-line-02.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "もっと離れたところから　浮遊島を見たことはあるんだけど / ちゃんと街の中から　見下ろしたことが今までなくて",
    "textZh": "我以前从更远的地方看过浮游岛，但还从来没有好好从城市里往下看过。",
    "durationSec": 7.09
  },
  {
    "id": "line-03",
    "sound": "motions/tap/tap-chtholly-line-03.wav",
    "motionIndex": 2,
    "expression": "smile",
    "textJa": "夢も叶ったし　いい思い出もできたし / 思い残すことはもうないかな / 今日は本当にありがとう",
    "textZh": "愿望也实现了，也留下了美好的回忆，已经没什么遗憾了。今天真的谢谢你。",
    "durationSec": 6.63
  },
  {
    "id": "line-04",
    "sound": "motions/tap/tap-chtholly-line-04.wav",
    "motionIndex": 0,
    "expression": "neutral",
    "textJa": "こーら　何してるの　キミたち / 「そっとしておきなさい」って　言われてたわよね",
    "textZh": "喂，你们在做什么？不是说过要让他一个人静一静吗？",
    "durationSec": 5.02
  },
  {
    "id": "line-05",
    "sound": "motions/tap/tap-chtholly-line-05.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "ごめんなさい　うちのチビたちが騒いで / でも　あまり甘やかさないでね / あの子たち　すぐ調子に乗るんだから",
    "textZh": "对不起，我家那些小家伙吵到你了。不过也别太惯着她们，她们很容易得意忘形的。",
    "durationSec": 7.66
  },
  {
    "id": "line-06",
    "sound": "motions/tap/tap-chtholly-line-06.wav",
    "motionIndex": 2,
    "expression": "neutral",
    "textJa": "えっと…　それから…　その… / クトリ…　クトリよ　私の名前",
    "textZh": "那个……还有……那个……我是珂朵莉，珂朵莉，这是我的名字。",
    "durationSec": 6.55
  },
  {
    "id": "line-07",
    "sound": "motions/tap/tap-chtholly-line-07.wav",
    "motionIndex": 0,
    "expression": "neutral",
    "textJa": "なんかこう　すっごくいまさらだけど / あの街で　自己紹介とかしてなかったから",
    "textZh": "虽然现在说这个好像很晚了，不过在那座城市里还没有正式自我介绍。",
    "durationSec": 5.95
  },
  {
    "id": "line-08",
    "sound": "motions/tap/tap-chtholly-line-08.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "そんなことも知らないで　ここに来たの？ / 知らないのにあの子たちに　付き合ってあげてたの？",
    "textZh": "你连那种事都不知道就来到这里了吗？明明不知道，还陪那些孩子一起闹？",
    "durationSec": 5.58
  },
  {
    "id": "line-09",
    "sound": "motions/tap/tap-chtholly-line-09.wav",
    "motionIndex": 2,
    "expression": "neutral",
    "textJa": "もしかしてあなた… / その場の勢いだけで　動いちゃう人？ / まあいいわ　教えてあげる",
    "textZh": "难道你是那种只凭一时气势就行动的人？算了，我告诉你吧。",
    "durationSec": 6.59
  },
  {
    "id": "line-10",
    "sound": "motions/tap/tap-chtholly-line-10.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "それじゃ　明日からよろしく / 私たちの管理者さん",
    "textZh": "那么，从明天开始请多关照了，我们的管理者先生。",
    "durationSec": 4.76
  },
  {
    "id": "line-11",
    "sound": "motions/tap/tap-chtholly-line-11.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "別に…　心配なんて… / 管理者とか　関係なしにいらないし / どうせ　時間は残ってないし",
    "textZh": "我才不是担心你……不管是不是管理者都不需要，反正也没剩多少时间了。",
    "durationSec": 8.0
  },
  {
    "id": "line-12",
    "sound": "motions/tap/tap-chtholly-line-12.wav",
    "motionIndex": 2,
    "expression": "sad",
    "textJa": "もし…　もしもよ / 私があと５日で死んじゃうとしたら / もうちょっと優しくしてくれる？",
    "textZh": "如果……我是说如果，我还有五天就会死的话，你会不会对我稍微温柔一点？",
    "durationSec": 7.57
  },
  {
    "id": "line-13",
    "sound": "motions/tap/tap-chtholly-line-13.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "キス…させろとか言ったら　どうする？",
    "textZh": "如果我说“让我亲你一下”，你会怎么办？",
    "durationSec": 4.81
  },
  {
    "id": "line-14",
    "sound": "motions/tap/tap-chtholly-line-14.wav",
    "motionIndex": 1,
    "expression": "smile",
    "textJa": "だったら安売りのうちに買い占めなさいよ / 賢い買い物の基本でしょ",
    "textZh": "那就在打折的时候一口气买下来啊，这是聪明购物的基本吧。",
    "durationSec": 4.61
  },
  {
    "id": "line-15",
    "sound": "motions/tap/tap-chtholly-line-15.wav",
    "motionIndex": 2,
    "expression": "neutral",
    "textJa": "何　今の… / えっと　よく分からなかった",
    "textZh": "刚才那是什么……那个，我没太搞明白。",
    "durationSec": 6.61
  },
  {
    "id": "line-16",
    "sound": "motions/tap/tap-chtholly-line-16.wav",
    "motionIndex": 0,
    "expression": "neutral",
    "textJa": "ただの散歩よ　何してるの / 適合者の許可も無しに勝手して",
    "textZh": "只是散步而已，你在做什么？没有适合者许可就擅自行动。",
    "durationSec": 5.64
  },
  {
    "id": "line-17",
    "sound": "motions/tap/tap-chtholly-line-17.wav",
    "motionIndex": 1,
    "expression": "sad",
    "textJa": "じゃあ　お言葉に甘えて言わせて / え～とねえ　強くなんてなりたくない",
    "textZh": "那我就恭敬不如从命地说了。嗯……我才不想变强呢。",
    "durationSec": 6.9
  },
  {
    "id": "line-18",
    "sound": "motions/tap/tap-chtholly-line-18.wav",
    "motionIndex": 2,
    "expression": "smile",
    "textJa": "へーん　目いっぱい素直にしてるわよ / それくらい分かれ　バカ",
    "textZh": "哼，我已经非常坦率了。连这都不懂，笨蛋。",
    "durationSec": 5.59
  },
  {
    "id": "line-19",
    "sound": "motions/tap/tap-chtholly-line-19.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "この前食堂で　お菓子作ってたでしょ / それなら　バターケーキって作れる？",
    "textZh": "你之前在食堂做过点心吧？那你会做黄油蛋糕吗？",
    "durationSec": 5.58
  },
  {
    "id": "line-20",
    "sound": "motions/tap/tap-chtholly-line-20.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "でも…通信晶石って　こっちの格好向こうにも見えるんでしょう",
    "textZh": "可是……通信晶石不是会把我这边的样子也传过去吗？",
    "durationSec": 4.99
  },
  {
    "id": "line-21",
    "sound": "motions/tap/tap-chtholly-line-21.wav",
    "motionIndex": 2,
    "expression": "neutral",
    "textJa": "でも最低限のマナーというか… / 心の準備が…",
    "textZh": "至少也要有最低限度的礼仪，或者说……心理准备……",
    "durationSec": 5.19
  },
  {
    "id": "line-22",
    "sound": "motions/tap/tap-chtholly-line-22.wav",
    "motionIndex": 0,
    "expression": "neutral",
    "textJa": "なななな何？ / ちょっと　痛い　苦しい　息できない",
    "textZh": "什什什什么？等一下，好痛、好难受、喘不过气。",
    "durationSec": 5.59
  },
  {
    "id": "line-24",
    "sound": "motions/tap/tap-chtholly-line-24.wav",
    "motionIndex": 2,
    "expression": "smile",
    "textJa": "ううん　そういうのじゃないから　心配かけてごめんなさい",
    "textZh": "不是，不是那种事。让你担心了，对不起。",
    "durationSec": 5.39
  },
  {
    "id": "line-25",
    "sound": "motions/tap/tap-chtholly-line-25.wav",
    "motionIndex": 0,
    "expression": "neutral",
    "textJa": "いっいらない　今あれされたらたぶん腰抜かしちゃうし / そこ　興味持たない",
    "textZh": "不、不需要。现在要是被那样做，我大概会腿软的。那边，不要感兴趣。",
    "durationSec": 5.42
  },
  {
    "id": "line-26",
    "sound": "motions/tap/tap-chtholly-line-26.wav",
    "motionIndex": 1,
    "expression": "smile",
    "textJa": "キミの戦い方とその強さは / たぶん　今この世界で　私が一番よく知ってる",
    "textZh": "你的战斗方式和强大，大概现在这个世界上我最清楚。",
    "durationSec": 6.63
  },
  {
    "id": "line-27",
    "sound": "motions/tap/tap-chtholly-line-27.wav",
    "motionIndex": 2,
    "expression": "sad",
    "textJa": "待ちなさいよ　なんなのよそれ / 約束したじゃない　行かないで",
    "textZh": "等一下，你这算什么？我们不是约好了嘛，不要走。",
    "durationSec": 5.88
  },
  {
    "id": "line-28",
    "sound": "motions/tap/tap-chtholly-line-28.wav",
    "motionIndex": 0,
    "expression": "sad",
    "textJa": "行くと怒る　ものすごく怒る / 絶対離さない　バカ　バカバカ",
    "textZh": "你要是走了我会生气，非常非常生气。我绝对不会放手，笨蛋，笨蛋笨蛋。",
    "durationSec": 6.37
  },
  {
    "id": "line-30",
    "sound": "motions/tap/tap-chtholly-line-30.wav",
    "motionIndex": 2,
    "expression": "sad",
    "textJa": "私？ / 私の名前はね… / あれ…　私は　誰だっけ？",
    "textZh": "我？我的名字是……咦，我是谁来着？",
    "durationSec": 6.29
  },
  {
    "id": "line-31",
    "sound": "motions/tap/tap-chtholly-line-31.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "うん　おはよう / いや　今朝の寝癖がひどくってね / それより　朝の稽古はどうだったの",
    "textZh": "嗯，早上好。不是啦，今天早上的睡相把头发弄得很糟。比起这个，早上的训练怎么样？",
    "durationSec": 7.88
  },
  {
    "id": "line-32",
    "sound": "motions/tap/tap-chtholly-line-32.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "えっと　それはほら / あんまりほかの子たちに子どもっぽく見られたくなかったし",
    "textZh": "那个，这个嘛……我不太想被其他孩子看起来像小孩子。",
    "durationSec": 5.83
  },
  {
    "id": "line-33",
    "sound": "motions/tap/tap-chtholly-line-33.wav",
    "motionIndex": 2,
    "expression": "smile",
    "textJa": "甘くていい匂い / おいしそう / これ　本当に食べてもいいやつだよね",
    "textZh": "好甜的香味。看起来很好吃。这个，真的是可以吃的东西吧？",
    "durationSec": 6.43
  },
  {
    "id": "line-35",
    "sound": "motions/tap/tap-chtholly-line-35.wav",
    "motionIndex": 1,
    "expression": "smile",
    "textJa": "いけない　胡椒 / あれ　胡椒…どれだっけ",
    "textZh": "不好，胡椒。咦，胡椒……是哪一个来着？",
    "durationSec": 5.7
  },
  {
    "id": "line-36",
    "sound": "motions/tap/tap-chtholly-line-36.wav",
    "motionIndex": 2,
    "expression": "smile",
    "textJa": "ちょうど買いたい物があったしね / みんなからもいろいろ頼まれたし / キミと２人きりで外を歩くなんて　めったいにないしね",
    "textZh": "正好有想买的东西，大家也拜托我买了不少。能和你两个人一起在外面走，这种机会很少呢。",
    "durationSec": 7.69
  },
  {
    "id": "line-37",
    "sound": "motions/tap/tap-chtholly-line-37.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "場所がどことか関係ない / 私は　キミと一緒にいたいだけなんだから",
    "textZh": "地点在哪里并不重要，我只是想和你在一起而已。",
    "durationSec": 4.82
  },
  {
    "id": "line-38",
    "sound": "motions/tap/tap-chtholly-line-38.wav",
    "motionIndex": 1,
    "expression": "neutral",
    "textJa": "私も行く / 私も行くって言ったの / 分かってる / でも帰りを待つだけが嫌なのは　私も同じよ",
    "textZh": "我也要去。我说了我也要去。我知道，可是我也一样讨厌只能等你回来。",
    "durationSec": 6.71
  },
  {
    "id": "line-39",
    "sound": "motions/tap/tap-chtholly-line-39.wav",
    "motionIndex": 2,
    "expression": "smile",
    "textJa": "愛人？ / 愛人… / それでもいいかも / そうね　じゃあやっぱり奥さん",
    "textZh": "爱人？爱人……那样也可以吧。是啊，那果然还是当妻子吧。",
    "durationSec": 6.42
  },
  {
    "id": "line-40",
    "sound": "motions/tap/tap-chtholly-line-40.wav",
    "motionIndex": 0,
    "expression": "smile",
    "textJa": "私もキミから離れない / ずっと…　ずっと一緒だよ",
    "textZh": "我也不会离开你。永远……永远在一起哦。",
    "durationSec": 4.77
  }
];

export const CHTHOLLY_TAP_MOTION_COUNT = 3;
