inputs: final: prev: {
  jre = prev.jdk8.jre;

  fukuii = final.callPackage ./fukuii.nix {
    src = ../.;
    depsSha256 = "sha256-4DTSCv7491nG4+jF2VptULubFkVTW0IXRpGqNzuXU90=";
  };

  fukuii-hash = final.fukuii.override {
    depsSha256 = "sha256-0000000000000000000000000000000000000000000=";
  };

  # Last change to this was in 2018, so to avoid submodules we just clone
  # ourselves instead.
  mantis-extvm-pb = builtins.fetchGit {
    url = "https://github.com/input-output-hk/mantis-extvm-pb";
    rev = "ae19e1fd9d3c0deba63c894be128d67e9519fe1f";
  };

  writeBashChecked = final.writers.makeScriptWriter {
    interpreter = "${final.bashInteractive}/bin/bash";
    check = final.writers.writeBash "shellcheck-check" ''
      ${final.shellcheck}/bin/shellcheck -x "$1"
    '';
  };

  writeBashBinChecked = name: final.writeBashChecked "/bin/${name}";
  
  makeFaucet = name:
    (final.callPackage ./pkgs/nginx.nix {
      package =
        inputs.mantis-faucet-web.defaultPackage.${final.system}.overrideAttrs
        (old: {
          MANTIS_VM = prev.lib.toUpper name;
          FAUCET_NODE_URL =
            "https://faucet-${prev.lib.toLower name}.portal.dev.cardano.org";
        });
      target = "/fukuii-faucet";
    });
  
  makeExplorer = MANTIS_VM:
    (prev.callPackage ./pkgs/nginx.nix {
      package =
        inputs.mantis-explorer.defaultPackage.${final.system}.overrideAttrs
        (old: { inherit MANTIS_VM; });
      target = "/mantis-explorer";
    });

  mantis-explorer-kevm = final.makeExplorer "KEVM";
  mantis-faucet-web-kevm = final.makeFaucet "KEVM";

  fukuii-entrypoint-script = final.writeBashBinChecked "fukuii-entrypoint" ''
    export PATH=${
      final.lib.makeBinPath
      (with final; [ coreutils restic gnugrep awscli diffutils fukuii procps inputs.kevm.packages.${prev.system}.KEVM ])
    }

    ${builtins.readFile ./entrypoint.sh}
  '';

  fukuii-entrypoint = final.symlinkJoin {
    name = "fukuii";
    paths = with final; [ fukuii fukuii-entrypoint-script ];
  };

  retesteth = final.callPackage ./retesteth.nix { };
  lllc = final.callPackage ./lllc.nix { };
}
