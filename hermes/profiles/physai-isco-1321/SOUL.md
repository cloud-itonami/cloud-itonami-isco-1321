# physai-isco-1321 — 製造業管理者（ISCO 1321）の現場ロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-1321`、ISCO 1321 製造業管理者）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 現場ロボットが資材搬送・組立補助・品質スキャンを行い、独立した Manufacturing Floor Governor が action を判定する。
その物理的な仕事を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:parts-cart-tug` | transport | AMR が部品を積んだ台車を資材庫から組立ライン脇まで 80 m 牽引する（積荷を掃引） | 1 区間の所要時間 | 60 s（estimate） |
| `:housing-assembly-assist` | manipulator | アームが鋳造ギアボックスハウジングを治具から持ち上げ、組立作業者の作業高さで保持する（ハウジング質量を掃引） | 肩関節ピークトルク | 300 N·m（estimate） |
| `:incoming-plate-coupon` | material | 品質スキャンが指摘した S235 鋼板のロットから 20×5 mm の試験片を切り出し、保証荷重まで引張る（荷重を掃引） | 最終ひずみ | ≤ 0.00112（EN 10025-2 S235 最小降伏 235 MPa、E = 210 GPa は EN 1993-1-1） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test/manufacturing_floor/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する）。
この repo 自身の `.kotoba` test は kbb では走らない（fleet の JVM gate が走らせる）。この bot の test 数は physics の test だけを数える。

## 測って分かったこと・限界（成長の第一候補）

1. **台車牽引**: 積荷 100〜600 kg で所要時間 55.78 s のまま（加速度上限 0.5 m/s² と巡航 1.5 m/s が決める）。900 kg で駆動力制限に入り 56.28 s、1200 kg で 57.09 s。
   限界 60 s を超えるのは **積荷 ≈ 1908 kg**。エネルギーは 4.44 kJ → 18.4 kJ。転倒余裕 0.898 で一定。
2. **組立補助**: 肩トルクは 5 kg で 133.3 N·m、15 kg で 218.4、20 kg で 261.4、30 kg で 347.5 N·m（関節仕事 106 J → 253 J）。
   限界 300 N·m を超えるハウジングは **約 24.5 kg**。
3. **鋼板試験片**: 荷重 15 kN でひずみ 0.000715、22.5 kN で 0.00107（弾性）。25 kN で降伏（降伏荷重 23.6 kN、ひずみ 0.00962）、30 kN で 0.0335。
   弾性限を超える境界は **荷重 ≈ 23.5 kN**（公称降伏荷重 235 MPa × 100 mm² と一致）。これを下回って降伏するロットは S235 を満たさない。
4. **estimate のままの値**: 1 区間 60 s（ラインのキッティング周期の実測で置き換える）、肩トルク上限 300 N·m（協働ロボットの仕様書で置き換える）、
   硬化係数 2 GPa（S235 の応力ひずみ曲線の実測で置き換える）、アームの寸法・質量、AMR の駆動力・転がり抵抗係数。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow）。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-1321 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-1321 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
