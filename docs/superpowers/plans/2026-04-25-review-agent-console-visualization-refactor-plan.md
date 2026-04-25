# Review-Agent 鎺у埗鍙版墦鍗?/ 鍙鍖栬緭鍑洪噸鏋勮鍒?
> 鎬ц川锛氳璁′笌瀹炴柦璁″垝鏂囨。銆傛湰璁″垝鎺ュ湪 prompt 閲嶆瀯鏂规涔嬪悗锛岀洰鏍囨槸鏀瑰杽 review-agent 宸ヤ綔鏃剁殑鎺у埗鍙板彲璇绘€э紱涓嶆敼鍙?agent 鍐崇瓥閫昏緫锛屼笉寮曞叆鏂?orchestrator锛屼笉鎶婃棩蹇楃郴缁熸敼鎴愭柊鐨勫墠绔骇鍝併€?
## 1. 鐩爣

褰撳墠 review-agent 鐨勬帶鍒跺彴杈撳嚭宸茬粡鏈夋渶灏忓彲鐢ㄧ殑杩涘害绾匡紝浣嗕粛瀛樺湪涓変釜闂锛?
1. 浜嬩欢鎵佸钩锛屽儚鏃ュ織娴侊紝涓嶅儚 agent trace銆?2. 缂哄皯鈥滆疆娆?/ 闃舵 / 閫夋嫨鍘熷洜 / 淇閲嶈瘯 / 澶辫触褰掑洜鈥濈殑缁撴瀯鍖栧彲瑙嗕笂涓嬫枃銆?3. 寰堥毦涓€鐪肩湅鍑猴細
   - 褰撳墠鍦ㄥ鍝釜 chunk
   - 绗嚑杞?next-step
   - 杩欒疆涓轰粈涔堥€夎繖涓伐鍏?   - 宸ュ叿鏄惁鎴愬姛
   - 鏄惁杩涘叆 repair / replan / human review / project completion

鏈鍒掔殑鐩爣鏄鎺у埗鍙拌緭鍑哄湪涓嶆敼 agent 涓婚€昏緫鐨勫墠鎻愪笅锛岃揪鍒扳€滀汉绫诲彲浠ラ『鐫€涓€杞竴杞湅鎳?agent 鍋氫簡浠€涔堛€佷负浠€涔堣繖鏍峰仛銆佺粨鏋滃浣曗€濈殑绋嬪害銆?
## 2. 涓嶅彲鐮村潖鐨勮竟鐣?
1. 涓嶆敼 review-agent 鐨勪骇鍝佸畾浣嶃€?2. 涓嶆敼宸ュ叿闆嗗悎锛屼笉鏀瑰閮ㄥ伐鍏峰崗璁€?3. 涓嶆敼 `AutonomousProjectReviewAgent` 鐨勪富鎺у埗娴佽涔夈€?4. 涓嶆妸 visualizer 鎻愬崌鎴愭柊鐨勮皟搴︿腑蹇冦€?5. 涓嶈鈥滄帶鍒跺彴杈撳嚭浼樺寲鈥濆弽鍚戞薄鏌?prompt / validator / executor 鐨勪笟鍔″垽鏂€?6. 楂樺眰鍙鍖栦簨浠剁殑鍙戝嚭鏉冨彧灞炰簬 `AutonomousProjectReviewAgent`锛涘叾浠?service 濡傞渶鏆撮湶鍙鍖栦俊鎭紝鍙兘閫氳繃鏃㈡湁鎵胯浇浣撴垨涓撶敤鍙璇婃柇瀵硅薄鍥炰紶锛屼笉鐩存帴渚濊禆 visualizer銆?7. `round` 鐨勫敮涓€鏉ユ簮鏄?`ProjectReviewRuntimeSession.currentFocusRound`锛況epair / proposal / local replan 榛樿闄勭潃鍦ㄥ綋鍓?round 涓嬶紝涓嶅崟鐙€掑 round銆?8. 淇濇寔榛樿鏈嶅姟鍦烘櫙鍙鐢ㄦ垨浣庡櫔闊筹紱涓嶈寮哄埗鎵€鏈夋寮忚繍琛岄兘鎵撳嵃澶ф trace銆?9. V1 / V2 浼樺厛澶嶇敤 `ReviewToolExecutionResult`銆乣ProjectReviewRuntimeSession.processTrail` 涓庡凡鍒嗙被寮傚父绫诲瀷鎵胯浇鍙鍖栨墍闇€淇℃伅锛涘彧鏈夎繖浜涙棦鏈夎浇浣撴棤娉曠ǔ瀹氳〃杈?round / repair / proposal 淇℃伅鏃讹紝鎵嶅厑璁告柊澧炲彧璇?trace / diagnostics DTO锛屼笖 DTO 鍙敱 agent 娑堣垂锛屼笉杩涘叆 persistence / resume / 澶栭儴鍗忚銆?
## 3. 鐜扮姸涓庨棶棰?
褰撳墠杈撳嚭閾捐矾寰堢獎锛?
1. `ReviewRuntimeVisualizer` 鍙湁锛?   - `projectStarted`
   - `focusSelected`
   - `toolCalled`
   - `toolCompleted`
   - `projectFinished`
2. `ConsoleReviewRuntimeVisualizer` 鍙槸鎶婅繖浜涗簨浠舵墦鍗版垚 `[review-agent] event=... key=value` 鐨勫崟琛屾枃鏈€?
婧愮爜渚濇嵁锛?
1. [ReviewRuntimeVisualizer.java](../../src/main/java/io/quillloom/application/postdraft/review/service/ReviewRuntimeVisualizer.java:7)
2. [ConsoleReviewRuntimeVisualizer.java](../../src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java:16)
3. [AutonomousProjectReviewAgent.java](../../src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java:136)

鍥犳鐜扮姸鐨勯棶棰樹笉鏄€滃畬鍏ㄦ病杈撳嚭鈥濓紝鑰屾槸锛?
1. 缂哄皯杞鎰熴€?2. 缂哄皯闃舵鎰熴€?3. 缂哄皯鍙壂鎻忕殑灞傜骇缁撴瀯銆?4. `reason / arguments / summary` 閮借鍘嬫墎鍦ㄤ竴琛岄噷锛岄暱鍐呭鍙鎬у樊銆?5. repair / structured-output failure / proposal special path 娌℃湁琚竻妤氬彲瑙嗗寲銆?
## 4. 鍊欓€夋柟妗?
## 4.1 鏂规 A锛氱户缁淮鎸佸崟琛?key=value锛屽彧鍋氬瓧娈垫墿鍏?
鍋氭硶锛?
1. 淇濇寔鐜版湁 `[review-agent] event=...` 鍗曡鏍煎紡銆?2. 澧炲姞鏇村瀛楁锛屼緥濡?`focusRound / state / strategy / pending / completed / problemTypes / gateSummary`銆?
浼樼偣锛?
1. 鏀瑰姩鏈€灏忋€?2. 瀵圭幇鏈?grep / log 鏀堕泦鏈€鍙嬪ソ銆?
缂虹偣锛?
1. 鍐嶆墿瀛楁鍚庝細鏇村儚鏈哄櫒鏃ュ織锛屼笉鍍?agent 宸ヤ綔杞ㄨ抗銆?2. 闀?`reason / summary / questionForHuman` 浠嶇劧闅捐銆?3. 杞銆乺epair銆乻pecial path 鐨勫彲瑙嗗眰绾т粛鐒跺緢寮便€?
缁撹锛?
1. 涓嶆帹鑽愪綔涓轰富鏂规銆?
## 4.2 鏂规 B锛氬湪鐜版湁 visualizer 閽╁瓙涓婂仛鈥滃垎鍧楀紡缁堢 trace鈥?
鍋氭硶锛?
1. 缁х画淇濈暀 `ReviewRuntimeVisualizer` 浣滀负杈撳嚭杈圭晫銆?2. 鎶婃瘡涓噸瑕佷簨浠朵粠鍗曡 key=value锛屾敼涓烘洿绋冲畾鐨勨€滃潡鐘惰緭鍑衡€濄€?3. 杈撳嚭鎸夊眰绾х粍缁囷細
   - 椤圭洰绾?   - focus 绾?   - round 绾?   - tool call 绾?   - repair / failure / completion 绾?4. 姣忓潡鍙樉绀烘渶鍏抽敭鐨勫嚑琛岋紝闀挎枃鏈仛瑁佸壀棰勮銆?
浼樼偣锛?
1. 涓嶆敼涓婚€昏緫锛屽彧閲嶆瀯鍛堢幇灞傘€?2. 浜虹溂鍙鎬ф槑鏄炬彁鍗囥€?3. 鍙互娓呮琛ㄨ揪 round / phase / action / result銆?4. 涓庣幇鏈?prompt dump銆乭istoryLog銆乼ranscriptStore 鑳藉舰鎴愪簰琛ャ€?
缂虹偣锛?
1. 闇€瑕佺粰 visualizer 澧炲姞鏇寸粏鐨勪簨浠舵垨鏇翠赴瀵岀殑涓婁笅鏂囥€?2. 闇€瑕佸皬蹇冩帶鍒惰緭鍑洪噺锛岄伩鍏嶅張鍙樺櫔闊炽€?
缁撹锛?
1. 鎺ㄨ崘浣滀负涓绘柟妗堛€?
## 4.3 鏂规 C锛氬仛浜や簰寮?TUI / Web 鍙鍖?
鍋氭硶锛?
1. 鏂板缓鏇村鏉傜殑缁堢 UI 鎴?Web 闈㈡澘銆?2. 閫氳繃浜嬩欢娴佹垨鎸佷箙鍖栨棩蹇楅┍鍔ㄦ樉绀恒€?
浼樼偣锛?
1. 鐞嗚涓婂睍绀鸿兘鍔涙渶寮恒€?
缂虹偣锛?
1. 鏄庢樉瓒呭嚭褰撳墠鑼冨洿銆?2. 闇€瑕佹柊鎶€鏈潰涓庢柊杩愯鍏ュ彛銆?3. 寰堝鏄撴粦鍚戞柊鐨勪骇鍝佸眰鍜?orchestrator 杈呭姪灞傘€?
缁撹锛?
1. 鏄庣‘涓嶅湪鏈鍒掕寖鍥村唴銆?
## 5. 鎺ㄨ崘鏂瑰悜

鎺ㄨ崘閲囩敤鏂规 B锛?
**淇濈暀鐜版湁 `ReviewRuntimeVisualizer` 杈圭晫锛屽湪鍏朵笂鍋氱浜岀増鎺у埗鍙?trace 淇℃伅鏋舵瀯閲嶆瀯銆?*

杩欐潯璺嚎鐨勬牳蹇冨垽鏂槸锛?
1. 闂涓昏鍦ㄢ€滀俊鎭粍缁囨柟寮忓樊鈥濓紝涓嶆槸鈥滄病鏈変簨浠舵鏋垛€濄€?2. 鐜版湁 `AutonomousProjectReviewAgent` 宸茬粡鎻愪緵浜?project/focus/tool 鐨勫叧閿挬瀛愩€?3. 鐪熸闇€瑕佽ˉ鐨勬槸锛?   - round 璇箟
   - special path 璇箟
   - failure / repair 璇箟
   - 鏇村彲鎵弿鐨勬帓鐗?4. 楂樺眰 trace 浜嬩欢浠嶅簲鐢?`AutonomousProjectReviewAgent` 缁熶竴鍙戝嚭锛涗笅灞?service 鍙礋璐ｆ妸鍙瀵熶俊鎭甫鍥?agent锛屼笉鐩存帴鎸佹湁 visualizer 渚濊禆銆?5. 鍙瀵熶俊鎭殑鎵胯浇浼樺厛绾у簲鍥哄畾涓猴細
   - 鍏堝鐢?`ReviewToolExecutionResult`
   - 鍐嶅鐢?`ProjectReviewRuntimeSession.processTrail`
   - 鍐嶅鐢ㄥ凡鍒嗙被寮傚父绫诲瀷
   - 鍙湁浠嶆棤娉曠ǔ瀹氳〃杈炬椂锛屾墠鏂板 agent 绉佹湁鐨勫彧璇?trace / diagnostics DTO

## 6. 鏂扮殑鎺у埗鍙拌緭鍑哄垎灞傝摑鍥?
鎺ㄨ崘鎶婃帶鍒跺彴杈撳嚭鍒嗘垚鍏被鍧楋細

## 6.1 椤圭洰鍧?
鏄剧ず锛?
1. `project_started`
2. `project_finished`
3. pending / completed 鎬昏
4. 鏈€缁?stopReason / diagnostics

鐢ㄩ€旓細

1. 璁╂搷浣滆€呯煡閬撻」鐩骇璧锋涓庢渶缁堢粨鏋溿€?
## 6.2 Focus 鍧?
鏄剧ず锛?
1. focus chunk
2. 褰撳墠 workingSet
3. current strategy
4. 褰撳墠 focus round 璧峰鏍囪

鐢ㄩ€旓細

1. 璁╂搷浣滆€呯煡閬?agent 姝ｅ湪瀹″摢涓腑蹇?chunk銆?
## 6.3 Round 鍧?
杩欐槸鏈閲嶆瀯鏈€閲嶈鐨勬柊灞傘€?
瀹氫箟绾︽潫锛?
1. `round` 鐨勫敮涓€璇箟鏉ユ簮鏄?`ProjectReviewRuntimeSession.currentFocusRound`銆?2. `focusRoundStarted(...)` 鐨勮Е鍙戞椂鏈哄繀椤讳笌 `currentFocusRound` 瀵归綈锛屼笉鑳戒负浜嗘樉绀烘晥鏋滆嚜閫?round銆?3. structured-output repair銆乨ecision repair銆乸roposal repair銆乸roposal `NOT_APPLICABLE` 鍚?local replan 榛樿闄勭潃鍦ㄥ綋鍓?round 涓嬫樉绀猴紝涓嶅崟鐙畻浣滄柊 round銆?4. `record_confirmed_terms` special path 浠嶅睘浜庡綋鍓?focus round 鐨勫眬閮ㄥ瓙杩囩▼锛屼笉鍗曠嫭鍗囨牸鎴愭柊鐨?round 缁村害銆?
鏄剧ず锛?
1. `focusRound`
2. 褰撳墠 state
3. 褰撳墠涓诲鏍＄淮搴︽垨闂鎽樿
4. 褰撳墠鍏抽敭闂ㄦ锛?   - anchorOnlyView
   - hasPreviousRead
   - hasNextRead
   - adjacentReadCount
   - pending/completed counts

鐢ㄩ€旓細

1. 鎶?next-step 鍐崇瓥鍙樻垚鈥滅 N 杞€濈殑鍙瀵熷璞°€?2. 缁欏悗闈㈢殑 tool call 鎻愪緵鏈疆璇銆?
## 6.4 Action 鍧?
鏄剧ず锛?
1. 閫夋嫨浜嗗摢涓?tool
2. 鏈€灏忓繀瑕佸弬鏁?3. `reason` 鐨勮鍓増
4. 鏄惁灞炰簬楂橀闄╁姩浣?
鐢ㄩ€旓細

1. 璁╀汉蹇€熺湅鍒扳€滆繖涓€杞?agent 鍐冲畾鍋氫粈涔堚€濄€?
## 6.5 Result 鍧?
鏄剧ず锛?
1. tool success / rejected
2. summary / rejectionReason
3. workingSet 鏄惁鎵╁紶
4. chunk 鏄惁瀹屾垚
5. 鏄惁杩涘叆 waiting-human / project-ready / completion

鐢ㄩ€旓細

1. 璁╀汉鐪嬪埌鈥滆繖涓姩浣滈€犳垚浜嗕粈涔堢姸鎬佸彉鍖栤€濄€?
## 6.6 Repair / Failure 鍧?
杩欐槸褰撳墠杈撳嚭鏈€缂虹殑涓€灞傘€?
鎺ㄨ崘鏄庣‘鏄剧ず锛?
1. structured-output failure
2. decision repair
3. `record_confirmed_terms` proposal special path
4. proposal `NOT_APPLICABLE` 鍚庣殑 local replan
5. tool rejection 涓?local replan hint
6. containable failure vs terminal failure

鐢ㄩ€旓細

1. 璁╂搷浣滆€呭垎娓咃細
   - agent 姝ｅ父鎺ㄨ繘
   - agent 鍦ㄤ慨鏍煎紡
   - agent 鍦ㄩ噸瑙勫垝
   - agent 鐪熺殑鍗′綇浜?
琛ュ厖绾︽潫锛?
1. repair / proposal / local replan 鍙綔涓哄綋鍓?round 涓嬬殑瀛愬潡鏄剧ず銆?2. 瀹冧滑涓嶈兘鍦ㄨ瑙変笂浼鎴愭柊鐨?focus round锛屽惁鍒欎細璇鎿嶄綔鑰呭 runtime 鎺ㄨ繘鐘舵€佺殑鍒ゆ柇銆?
## 7. 寤鸿杈撳嚭鏍煎紡

鎺ㄨ崘浣跨敤鈥滅煭鍧?+ 缂╄繘 + 鍥哄畾鏍囬鈥濈殑鏂囨湰鏍煎紡锛岃€屼笉鏄户缁墿鍏呭崟琛?key=value銆?
### 7.1 杈撳嚭妯″紡

鎺у埗鍙拌緭鍑鸿嚦灏戝垎涓夋。锛?
1. `OFF`
   - 涓嶈緭鍑哄潡鐘?trace
   - 閫傜敤浜庨粯璁ょ敓浜?/ 鏅€氭湇鍔?wiring
2. `COMPACT`
   - 杈撳嚭浣庡櫔闊虫憳瑕?   - 淇濈暀椤圭洰璧锋銆乫ocus 閫夋嫨銆佸叧閿?action/result銆乼erminal failure
   - 閫傜敤浜庨粯璁ゆ湰鍦版湇鍔℃垨杞婚噺瑙傚療
3. `TRACE`
   - 杈撳嚭瀹屾暣鍧楃姸 trace
   - 鍖呭惈 round / action / result / repair / containable failure
   - 鍙湪鏄惧紡鍚敤鏃跺紑鍚?
鍏煎鎬х害鏉燂細

1. 鑻ヤ繚鐣欑幇鏈夊崟琛?`[review-agent] event=...` 椋庢牸锛屽畠搴旇鏄庣‘瀹氫箟涓?`COMPACT` 鎴栫嫭绔?`LEGACY_LINE` 鍏煎妯″紡銆?2. 涓嶅厑璁稿湪鍧楃姸 `TRACE` 涔嬪鍐嶉粯璁ゅ彔鍔犲ぇ閲忛噸澶嶅崟琛屾棩蹇楋紝鍚﹀垯浼氶噸鏂板埗閫犲櫔闊炽€?
鏈€灏忎簨浠堕泦鍚堬細

1. `COMPACT` 鍙繚鐣欙細
   - `project_started`
   - `focus_selected`
   - `decisionProduced` 鐨?action 鎽樿
   - `toolCompleted` 鐨?result 鎽樿
   - `humanReviewRequested`
   - terminal failure
   - `project_finished`
2. `TRACE` 鎵嶆樉绀猴細
   - `focusRoundStarted(...)`
   - `focusRoundFinished(...)`
   - gate summary
   - action / result 璇︽儏鍧?   - repair 瀛愬潡
   - `record_confirmed_terms` proposal special path
   - containable failure 瀛愬潡
3. `COMPACT` 涓嶆樉绀?round 瀛愬潡銆乺epair 瀛愬潡銆乸roposal special path 瀛愬潡銆乧ontainable failure 瀛愬潡銆?4. `TRACE` 涓嶅簲鍐嶉粯璁ゅ彔鍔犲畬鏁存棫寮忓崟琛?event 娴侊紝鍚﹀垯浼氬舰鎴愬弻浠借緭鍑恒€?
寤鸿椋庢牸锛?
1. 姣忎釜 round 涓€涓皬鍧椼€?2. 姣忎釜 action / result 鎴愬鍑虹幇銆?3. 澶ф枃鏈彧鏄剧ず棰勮銆?4. 鍏抽敭淇″彿鍗曠嫭鎴愯锛屼緥濡傦細
   - `state=INVESTIGATING`
   - `tool=read_next_chunks count=1`
   - `repair=decision_repair`
   - `completion=focus_chunk_submitted`

涓嶅缓璁細

1. 鎶婂叏閮?session 瀛楁涓€娆℃€у€惧€掑埌鎺у埗鍙般€?2. 鎶婂畬鏁?prompt dump 鐩存帴鎵撳埌缁堢銆?3. 鎶?transcript replay 鍏ㄩ噺鎵撳嵃鎴愭€濈淮閾炬浛浠ｅ搧銆?
## 8. 鈥滄€濊€冧笌鍔ㄤ綔鈥濆彲瑙嗗寲杈圭晫

鐢ㄦ埛鏄庣‘甯屾湜鐪嬪埌鈥渁gent 鐨勬€濊€冧笌鍔ㄤ綔鈥濓紝浣嗚繖閲屽繀椤诲垝娓呰竟鐣屻€?
鎺ㄨ崘鏄剧ず锛?
1. agent 鐨?*鍐崇瓥鐞嗙敱鎽樿**
   - 鏉ヨ嚜 `ReviewToolDecision.reason`
   - 鏉ヨ嚜 evaluation 鐨?strategyReason
   - 鏉ヨ嚜 human request 鐨?questionForHuman / requestReason
2. agent 鐨?*闃舵鎺ㄨ繘鐘舵€?*
   - 褰撳墠鍦?investigation / evaluation / revision / self-check / completion 鍝竴娈?3. agent 鐨?*鍔ㄤ綔**
   - 璋冧簡鍝釜宸ュ叿
   - 宸ュ叿缁撴灉濡備綍

涓嶅缓璁樉绀猴細

1. 鍋囪杈撳嚭瀹屾暣 chain-of-thought銆?2. 鎶婃ā鍨嬪唴閮ㄩ暱绡囪嚜鐢辨帹鐞嗙洿鎺ヤ綔涓烘帶鍒跺彴鈥滄€濊€冨唴瀹光€濄€?3. 涓轰簡灞曠ず鈥滄€濊€冣€濊€屾墿寮犲閮ㄥ崗璁€?
鏈鍒掗噷鈥滅湅娓?agent 鐨勬€濊€冣€濆簲瑙ｉ噴涓猴細

1. 鐪嬫竻瀹冪殑闃舵
2. 鐪嬫竻瀹冪殑鍐崇瓥鐞嗙敱鎽樿
3. 鐪嬫竻瀹冪殑鍔ㄤ綔涓庣粨鏋?
鑰屼笉鏄毚闇蹭笉鍙帶鐨勫唴閮ㄦ帹鐞嗗叏鏂囥€?
## 9. 闇€瑕佽ˉ鍏呯殑鍙鍖栦簨浠?
褰撳墠 `ReviewRuntimeVisualizer` 鐨勯挬瀛愯繕涓嶅琛ㄨ揪 round / repair / special path銆?
鎺ㄨ崘鏂板鎴栭噸鏋勭殑浜嬩欢绫诲埆锛?
1. `focusRoundStarted(...)`
2. `decisionProduced(...)`
3. `repairTriggered(...)`
4. `localReplanTriggered(...)`
5. `containableFailureCaptured(...)`
6. `humanReviewRequested(...)`
7. `focusRoundFinished(...)`

浜嬩欢 ownership 纭害鏉燂細

1. 楂樺眰鍙鍖栦簨浠跺彧鍏佽鐢?`AutonomousProjectReviewAgent` 鍙戝嚭銆?2. `PromptBackedNextStepDecisionProvider`銆乺epair 鍒嗘敮銆乸roposal 鍒嗘敮銆乼ool executor 绛変笅灞?service 涓嶅簲鏂板 visualizer 渚濊禆銆?3. 鑻ヤ笅灞?service 闇€瑕佹毚闇?repair / rejection / proposal / local replan 淇℃伅锛屽簲浼樺厛閫氳繃 `ReviewToolExecutionResult`銆乣ProjectReviewRuntimeSession.processTrail` 涓庡凡鍒嗙被寮傚父绫诲瀷鎶婁俊鎭甫鍥?`AutonomousProjectReviewAgent`锛屽啀鐢?agent 缁熶竴杞垚鍙鍖栦簨浠躲€?4. 鍙湁杩欎簺鏃㈡湁鎵胯浇浣撴棤娉曠ǔ瀹氳〃杈炬墍闇€淇℃伅鏃讹紝鎵嶅厑璁告柊澧?agent 绉佹湁鐨勫彧璇?trace / diagnostics DTO銆?5. visualizer 鍙兘瑙傚療锛屼笉寰楀€掗€?service 涔嬮棿鏂板鍗忚皟鍏崇郴銆?
round 璇箟纭害鏉燂細

1. `focusRoundStarted(...)` / `focusRoundFinished(...)` 蹇呴』涓?`ProjectReviewRuntimeSession.currentFocusRound` 瀵归綈銆?2. repair / proposal / local replan 榛樿闄勭潃鍦ㄥ綋鍓?round 涓嬫樉绀猴紝涓嶅崟鐙€掑 round銆?3. 涓嶅厑璁告妸 structured-output repair 璇樉绀烘垚鏂扮殑 focus round銆?4. 涓嶅厑璁告妸 `record_confirmed_terms` special path 娣锋垚鐙珛 round 搴忓垪銆?
鎺ㄨ崘浼樺厛绾э細

1. 鍏堣ˉ round / repair / containable failure
2. 鍐嶈ˉ鏇寸粏鐨?strategy / gate summary

## 10. 鏂囦欢鑼冨洿寤鸿

浼樺厛鏀癸細

1. `src/main/java/io/quillloom/application/postdraft/review/service/ReviewRuntimeVisualizer.java`
2. `src/main/java/io/quillloom/application/postdraft/review/service/ConsoleReviewRuntimeVisualizer.java`
3. `src/main/java/io/quillloom/application/postdraft/review/service/AutonomousProjectReviewAgent.java`
4. 蹇呰鏃跺皯閲忚Е杈撅細
   - `ReviewToolExecutionResult`
   - 涓?visualizer 娉ㄥ叆鐩稿叧鐨?service / smoke wiring
   - 鍙 trace / diagnostics DTO锛堝鏋滅‘鏈夊繀瑕侊級

涓嶅缓璁墿鏁ｏ細

1. 涓嶆敼 tool protocol
2. 涓嶆敼 persistence schema
3. 涓嶆敼 prompt 缁撴瀯鏉ヤ笓闂ㄦ湇鍔℃帶鍒跺彴鏄剧ず
4. 涓嶆妸 console visualizer 缁戣繘鏍稿績涓氬姟鍒ゆ柇
5. 涓嶆妸 visualizer 渚濊禆娉ㄥ叆 `PromptBackedNextStepDecisionProvider` 鎴栧叾浠栦笅灞備笟鍔?service

## 11. 鍒嗛樁娈靛疄鏂介『搴?
### Phase V1锛氫俊鎭灦鏋勯噸鎺?
鐩爣锛?
1. 鎶婂崟琛岃緭鍑烘敼鎴愰」鐩潡 / focus 鍧?/ round 鍧?/ action/result 鍧椼€?
楠岃瘉锛?
1. smoke 杩愯鏃跺彲浠ラ『鐫€杞璇绘噦 agent 鍦ㄥ仛浠€涔堛€?
### Phase V2锛歳epair / failure 鍙鍖?
鐩爣锛?
1. 鏄庣‘鏄剧ず repair銆乸roposal special path銆乺eplan銆乧ontainment銆?
楠岃瘉锛?
1. 鍙戠敓 structured-output / proposal 闂鏃讹紝缁堢鍙竻妤氬垎杈ㄥけ璐ョ被鍨嬩笌鍚庣画璺緞銆?
### Phase V3锛氬叧閿俊鍙锋憳瑕?
鐩爣锛?
1. 琛ュ厖 gate summary銆乻trategy銆侀棶棰樻憳瑕併€乭uman request 鎽樿銆?
楠岃瘉锛?
1. 涓嶇湅 prompt dump锛屼篃鑳藉ぇ鑷寸悊瑙?agent 涓轰粈涔堥€夎繖涓伐鍏枫€?
## 12. 楠岃瘉鏂规

1. 鐜版湁 smoke / runner 鍦烘櫙涓嬫墜宸ヨ瀵熻緭鍑恒€?2. 涓?`ConsoleReviewRuntimeVisualizer` 澧炲姞瀹氬悜娴嬭瘯锛屾牎楠屽叧閿潡缁撴瀯銆?3. 閫変笁绫诲吀鍨嬭矾寰勫仛楠岃瘉锛?   - 姝ｅ父 investigation -> completion
   - evaluation -> revision -> self-check -> completion
   - repair / replan / human review 璺緞
4. 楠岃瘉 `OFF / COMPACT / TRACE` 涓夋。妯″紡琛屼负娓呮櫚锛屼笖榛樿鏈嶅姟 wiring 浣跨敤 `OFF` 鎴?`COMPACT`銆?5. 楠岃瘉澶辫触璺緞涓細
   - structured-output repair 涓嶄細鍐掑厖鏂颁竴杞?focus
   - proposal special path 浠嶈兘鐪嬪嚭鏄綋鍓?round 涓嬬殑灞€閮ㄩ摼璺?   - terminal failure 涓?containable failure 鍛堢幇涓嶅悓
6. 楠岃瘉瀹炵幇娌℃湁鎶?visualizer 鎵€闇€淇℃伅閲嶆柊缂栫爜鎴愯剢寮辩殑寮傚父瀛楃涓插崗璁紝涔熸病鏈夋妸 `processTrail` 婊ョ敤鎴愬崐缁撴瀯鍖栦簨浠舵€荤嚎銆?
## 13. 鎺ㄨ崘缁撹

杩欓」宸ヤ綔閫傚悎鎺ュ湪 prompt 閲嶆瀯涔嬪悗鍋氾紝浣嗗簲淇濇寔绐勮寖鍥达細

1. 瀹冩槸鈥滆繍琛屾椂鍙瀵熸€?/ 鎺у埗鍙?trace 閲嶆瀯鈥濓紝涓嶆槸鏂扮殑浜や簰浜у搧銆?2. 瀹冭В鍐崇殑鏄€滀汉鐪嬩笉鎳?agent 姝ｅ湪鍋氫粈涔堚€濓紝涓嶆槸鈥滄浛 agent 鎬濊€冣€濄€?3. 鏈€鍚堥€傜殑钀界偣鏄細
   - 鍦ㄧ幇鏈?visualizer 杈圭晫涓婂仛绗簩鐗堜俊鎭灦鏋?   - 鏄庣‘ round / action / result / repair / failure
   - 鎻愬崌鍙鎬э紝浣嗕笉鏀瑰彉涓氬姟楠ㄦ灦

