package com.aeronex.app

/** Official archives verified against catalog MD5; SHA-256 manifests generated
 * by tools/check_vosk_models.py. See docs/testing/2026-10-09-vosk-models.json. */
object VoskModels {
    val profiles: Map<String, LocalModelProfile> = mapOf(
        "vosk-en-in" to LocalModelProfile(
            "vosk-model-small-en-in-0.4.zip", 37573330L,
            "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            archiveFiles = mapOf(
                "README" to LocalModelFile(51L, "91074f233ab90e2d5c82c8a8ffd45d8c2cd9bf30c1d448f88556ff84daa25f70"),
                "am/final.mdl" to LocalModelFile(13767397L, "443be3283c6112fbb5628807f84e0f5b369056ea59fcbc5ce73b8dfe10e9e893"),
                "conf/mfcc.conf" to LocalModelFile(107L, "f4b7deca59c6f7076708a0c27f4000507db272c1233dadbce59cf8934b0d1b04"),
                "conf/model.conf" to LocalModelFile(289L, "dd7178da0e99fdbe489816c1e9702c3daa28e02accacc2993e0becb7af33b80c"),
                "graph/Gr.fst" to LocalModelFile(24029637L, "13cf46500ed9bf75e67bda2ecb3e6442f33db4c9ef0ba6b55414454b30b7e85c"),
                "graph/HCLr.fst" to LocalModelFile(10419698L, "e3a011f91b31c1c7fab8ee26bbbefc2896967789411c888d08e164fe70ff0d77"),
                "graph/disambig_tid.int" to LocalModelFile(72L, "d4e5cca8ecc7062a10f1458209c884957db77ef4aee84e51f641400b3c4a0493"),
                "graph/phones/word_boundary.int" to LocalModelFile(2751L, "08f6f114ca793823ada97d41026532a2458b96a63b8a690e440a5e07a256301e"),
                "ivector/final.dubm" to LocalModelFile(168048L, "37581005e41a33711e70b25de9badfe25fdf832285da09afde4fe9f591584cbe"),
                "ivector/final.ie" to LocalModelFile(8288887L, "8203f44c17fc9c5ca630d5198dbdac0aeba070b0623a48598abf363083286a76"),
                "ivector/final.mat" to LocalModelFile(22575L, "c066674f26aefe3d3bdfadca85aec7d4dd9f5b584ad3d4712852893e8a31063a"),
                "ivector/global_cmvn.stats" to LocalModelFile(550L, "c057702cbafa978adf8123c7ed4367ea3d65fbb84f2bcd5f58a94b0d5bb37058"),
                "ivector/online_cmvn.conf" to LocalModelFile(95L, "a2f3571754b64297cb7efb2e7ca3df61995c5a45fcbb97188f90613552bb2dfe"),
                "ivector/splice.conf" to LocalModelFile(35L, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756")
            )
        ),
        "vosk-te" to LocalModelProfile(
            "vosk-model-small-te-0.42.zip", 60544249L,
            "3e77b05becc7599a596ccebd8b22cb95eb4eca0cfca524827cdfbfd66af09497",
            "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip",
            archiveFiles = mapOf(
                "README" to LocalModelFile(260L, "b6f0ecb7e0a27be5930ad50e9e221a735ea4bf696ae36d853a0b569a8b834e2f"),
                "am/final.mdl" to LocalModelFile(16027940L, "bedbdd5490577e057ae5b48d06c2b02e04af5989bdf95cd002b47e4592c7c56c"),
                "conf/mfcc.conf" to LocalModelFile(153L, "6c326fea5741c2d78be12f76eb1b36ea2ace040da9e355e4cfe6bb80e5f7d5ab"),
                "conf/model.conf" to LocalModelFile(290L, "8f14cb1eeb07c762c371db648c6be688d347236155ca0f64fb13b6567a8ce81f"),
                "graph/Gr.fst" to LocalModelFile(64130105L, "44c8b4f0b025c45c50885e6269ed3d49488e58a1bb4e82b822e9606393de5332"),
                "graph/HCLr.fst" to LocalModelFile(37570114L, "544483db3afcfdd83ed4cc5130201b04c800ed7ed939cf2b2fd326588130e42c"),
                "graph/disambig_tid.int" to LocalModelFile(30L, "24937bf8f7ad8316c1d67ef297b32c81293aa068bf036598751d8fee0851beef"),
                "graph/phones/word_boundary.int" to LocalModelFile(1941L, "c39249476ebabde0c5e00d5c31845332804ef2b057d5e3f8ea29ea171a280dad"),
                "ivector/final.dubm" to LocalModelFile(168048L, "c8ef134f43231f9491bbe21d26254203a4ba10a8fdf371ca44e483924f9b0996"),
                "ivector/final.ie" to LocalModelFile(9927287L, "722366da63c52a81bf5b2ec6e9d366b46efae1626f0129e6646578dc2b1f70a5"),
                "ivector/final.mat" to LocalModelFile(44975L, "d42b3dd59797ec4ffc9e8caa713d2fba379ec0d96b4dca6b07ae62b159a94770"),
                "ivector/global_cmvn.stats" to LocalModelFile(1063L, "c454130f34cacb195ff993da8354f367bbc5b8abf979aae5c2b96d4f23b51699"),
                "ivector/online_cmvn.conf" to LocalModelFile(0L, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                "ivector/splice.conf" to LocalModelFile(35L, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756")
            )
        ),
        "vosk-hi" to LocalModelProfile(
            "vosk-model-small-hi-0.22.zip", 44458845L,
            "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48",
            "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            archiveFiles = mapOf(
                "README" to LocalModelFile(460L, "9ffae7d857a7671a0a53b9f6f432e7c312459fde02ca1f139d808fffc3d2644a"),
                "am/final.mdl" to LocalModelFile(15957444L, "c5d7ea51a074bc64430137a74fc8a0a06097df485a3d356cf37d2d78ef6bd712"),
                "conf/mfcc.conf" to LocalModelFile(128L, "31dab8bb1dea9b33e039988ac079dfe5d50ebaa4a94826b9f9adc8fe84b147c6"),
                "conf/model.conf" to LocalModelFile(289L, "d8d91d310a9659fed2d89f8ea4996af6163a43c3ab008cd278e4264120551712"),
                "graph/Gr.fst" to LocalModelFile(30692365L, "9b219e22fc7d3033ba1b175757a700d8f02139d75d7f57cc12bc318d53038ccf"),
                "graph/HCLr.fst" to LocalModelFile(25289514L, "4f36aad2566853473603abc66fac3985eff0860229c465540b348c22fcbbb4f5"),
                "graph/disambig_tid.int" to LocalModelFile(66L, "3f87fd5a92742b3827d2da31dae0874c4077e9f0011e507423b6f876d743e37b"),
                "graph/phones/word_boundary.int" to LocalModelFile(2481L, "b8f981d84d8765fb15003ac54ec0f9599efa65a00d6d4d60086e5969ad45ff01"),
                "ivector/final.dubm" to LocalModelFile(168048L, "e495541a7e99e78a42cd098adfd94a72b6118a8c0d3c5ea8fe17436b578c55d4"),
                "ivector/final.ie" to LocalModelFile(9927287L, "89d91ca6e99e7e4f067b3ef8ac1faa9ba73276c225d927aaa296492a03a639b7"),
                "ivector/final.mat" to LocalModelFile(44975L, "3009aea21b59ad36f86c89d68dd152264aa3a784260dbdace823d991f3ff71b4"),
                "ivector/global_cmvn.stats" to LocalModelFile(1074L, "a056b7592116d4a5e5fc6f872f8398190878bb6690bf486827fa79dabaf33f97"),
                "ivector/online_cmvn.conf" to LocalModelFile(0L, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                "ivector/splice.conf" to LocalModelFile(35L, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756")
            )
        )
    )

    fun profileForLanguage(language: String): String? = when (language.lowercase(java.util.Locale.ROOT)) {
        "en", "en-in", "en-us" -> "vosk-en-in"
        "te", "te-in" -> "vosk-te"
        "hi", "hi-in" -> "vosk-hi"
        else -> null
    }
}
