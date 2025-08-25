Synthetic KYC Test Images (PNG & JPG)
-------------------------------------
These images are artificial and safe for demo/testing. Place them under:
  src/main/resources/customer-docs/

Files:
- passport_valid.(png|jpg): Baseline valid sample.
- passport_expired.(png|jpg): EXP date set to 2019-05-01.
- passport_blurred.(png|jpg): Gaussian blur applied.
- passport_glare.(png|jpg): Overexposed white band simulating glare.
- passport_mrz_missing.(png|jpg): MRZ zone blank.
- passport_mrz_invalid.(png|jpg): MRZ line with checksum error.
- passport_cropped.(png|jpg): Cropped/partial edges.
- passport_lowres.(png|jpg): Downscaled & upscaled to induce artifacts.
- idcard_generic.(png|jpg): Generic ID-card style sample.
- passport_name_redacted.(png|jpg): Name area covered by black bar.
